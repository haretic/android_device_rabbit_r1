// SPDX-License-Identifier: MIT
// Copyright (c) 2026 haretic contributors
#include "cat_kenny_r1_tunnels_zt_ZtNode.h"
#include "ZeroTierOne.h"
#include <nlohmann/json.hpp>
#include <arpa/inet.h>
#include <atomic>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include <cstdio>

using Json = nlohmann::json;
namespace {
struct Argument { const char* message; };
struct Busy {};
void require(bool ok, const char* message) { if (!ok) throw Argument{message}; }
void exception(JNIEnv* e, const char* type, const char* message) {
    if (!e->ExceptionCheck()) { jclass c=e->FindClass(type); if(c) { e->ThrowNew(c,message); e->DeleteLocalRef(c); } }
}
#define END_JNI(value) \
    catch (const Argument& x) { exception(e,"java/lang/IllegalArgumentException",x.message); } \
    catch (const Busy&) { exception(e,"java/lang/IllegalStateException","serialize node calls; no callback reentry"); } \
    catch (const std::bad_alloc&) { exception(e,"java/lang/OutOfMemoryError","native allocation failed"); } \
    catch (...) { exception(e,"java/lang/IllegalStateException","native node operation failed"); } \
    return value;

std::string hex(uint64_t n, int width) { char b[32]; std::snprintf(b,sizeof b,"%0*llx",width,(unsigned long long)n); return b; }
std::string ip(const sockaddr_storage& a) {
    char b[INET6_ADDRSTRLEN]={}; const void* p=nullptr;
    if(a.ss_family==AF_INET) p=&reinterpret_cast<const sockaddr_in*>(&a)->sin_addr;
    else if(a.ss_family==AF_INET6) p=&reinterpret_cast<const sockaddr_in6*>(&a)->sin6_addr;
    return p && inet_ntop(a.ss_family,p,b,sizeof b) ? b : "";
}
int port(const sockaddr_storage& a) {
    if(a.ss_family==AF_INET) return ntohs(reinterpret_cast<const sockaddr_in*>(&a)->sin_port);
    if(a.ss_family==AF_INET6) return ntohs(reinterpret_cast<const sockaddr_in6*>(&a)->sin6_port);
    return 0;
}
const char* statusName(int s) {
    switch(s) { case 0:return "REQUESTING_CONFIGURATION"; case 1:return "OK"; case 2:return "ACCESS_DENIED";
    case 3:return "NOT_FOUND"; case 4:return "PORT_ERROR"; case 5:return "CLIENT_TOO_OLD";
    case 6:return "AUTHENTICATION_REQUIRED"; default:return "UNKNOWN"; }
}
Json configJson(const ZT_VirtualNetworkConfig* c) {
    if(!c) return nullptr;
    Json addresses=Json::array(), routes=Json::array(), dns=Json::array(), groups=Json::array();
    for(unsigned i=0;i<c->assignedAddressCount && i<ZT_MAX_ZT_ASSIGNED_ADDRESSES;++i)
        addresses.push_back({{"address",ip(c->assignedAddresses[i])},{"prefix",port(c->assignedAddresses[i])}});
    for(unsigned i=0;i<c->routeCount && i<ZT_MAX_NETWORK_ROUTES;++i) {
        const auto& r=c->routes[i];
        routes.push_back({{"target",ip(r.target)},{"prefix",port(r.target)},{"via",ip(r.via)},{"flags",r.flags},{"metric",r.metric}});
    }
    for(unsigned i=0;i<ZT_MAX_DNS_SERVERS;++i) if(c->dns.server_addr[i].ss_family) dns.push_back(ip(c->dns.server_addr[i]));
    for(unsigned i=0;i<c->multicastSubscriptionCount && i<ZT_MAX_MULTICAST_SUBSCRIPTIONS;++i)
        groups.push_back({{"mac",hex(c->multicastSubscriptions[i].mac,12)},{"adi",c->multicastSubscriptions[i].adi}});
    return {{"nwid",hex(c->nwid,16)},{"mac",hex(c->mac,12)},{"name",c->name},{"status",c->status},
        {"statusName",statusName(c->status)},{"type",c->type},{"typeName",c->type==ZT_NETWORK_TYPE_PRIVATE?"PRIVATE":"PUBLIC"},
        {"mtu",c->mtu},{"dhcp",c->dhcp!=0},{"bridge",c->bridge!=0},{"broadcastEnabled",c->broadcastEnabled!=0},
        {"portError",c->portError},{"netconfRevision",c->netconfRevision},{"assignedAddresses",addresses},
        {"routes",routes},{"dns",{{"domain",c->dns.domain},{"servers",dns}}},{"multicastSubscriptions",groups},
        {"ssoEnabled",c->ssoEnabled},{"authenticationURL",c->authenticationURL},{"authenticationExpiryTime",c->authenticationExpiryTime}};
}
jstring javaJson(JNIEnv* e,const Json& j) {
    // ASCII JSON avoids NewStringUTF's modified-UTF8 mismatch for network names.
    std::string s=j.dump(-1,' ',true,Json::error_handler_t::replace); return e->NewStringUTF(s.c_str());
}
struct Context {
    ZT_Node* node=nullptr; jobject cb=nullptr; JNIEnv* env=nullptr;
    jmethodID get=nullptr,put=nullptr,wire=nullptr,frame=nullptr,config=nullptr,event=nullptr;
    std::atomic<bool> active{false}; int64_t lastNow=0;
    ~Context() { if(node) ZT_Node_delete(node); if(cb && env) env->DeleteGlobalRef(cb); }
};
std::mutex registryMutex;
std::map<jlong,std::shared_ptr<Context>> registry;
jlong nextHandle=1;
struct Entry {
    std::shared_ptr<Context> c;
    Entry(JNIEnv* e,jlong h) {
        { std::lock_guard<std::mutex> l(registryMutex); auto it=registry.find(h);
          require(it!=registry.end(),"unknown or deleted node handle"); c=it->second; }
        if(c->active.exchange(true)) throw Busy{};
        c->env=e;
    }
    ~Entry() { c->env=nullptr; c->active=false; }
    void now(jlong n) { require(n>=0 && n>=c->lastNow,"now must be increasing milliseconds in one clock domain"); c->lastNow=n; }
};
struct Locals {
    JNIEnv* e; bool ok;
    explicit Locals(Context* c):e(c->env),ok(e && !e->ExceptionCheck() && e->PushLocalFrame(16)==0) {}
    ~Locals() { if(ok) { e->ExceptionCheck(); e->PopLocalFrame(nullptr); } }
};
jbyteArray bytes(JNIEnv* e,const void* p,unsigned n) {
    if(n>0x7fffffffU || (n && !p)) return nullptr;
    auto b=e->NewByteArray(n); if(b && n) e->SetByteArrayRegion(b,0,n,reinterpret_cast<const jbyte*>(p)); return b;
}
jlongArray ids(JNIEnv* e,const uint64_t id[2]) {
    auto a=e->NewLongArray(2); jlong data[2]={(jlong)id[0],(jlong)id[1]}; if(a) e->SetLongArrayRegion(a,0,2,data); return a;
}
void putState(ZT_Node*,void* user,void*,ZT_StateObjectType type,const uint64_t id[2],const void* data,int len) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok) return;
    auto a=ids(l.e,id); auto b=len<0 ? nullptr : bytes(l.e,data,(unsigned)len);
    if(!l.e->ExceptionCheck()) l.e->CallVoidMethod(c->cb,c->put,(jint)type,a,b);
}
int getState(ZT_Node*,void* user,void*,ZT_StateObjectType type,const uint64_t id[2],void* buffer,unsigned capacity) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok) return -1;
    auto a=ids(l.e,id); if(l.e->ExceptionCheck()) return -1;
    auto b=(jbyteArray)l.e->CallObjectMethod(c->cb,c->get,(jint)type,a);
    if(l.e->ExceptionCheck() || !b) return -1;
    jsize n=l.e->GetArrayLength(b); if((unsigned)n>capacity) return -1;
    if(n) l.e->GetByteArrayRegion(b,0,n,(jbyte*)buffer);
    return l.e->ExceptionCheck() ? -1 : n;
}
int wireSend(ZT_Node*,void* user,void*,int64_t local,const sockaddr_storage* remote,const void* data,unsigned len,unsigned ttl) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok || !remote) return -1;
    const void* addr=nullptr; unsigned size=0;
    if(remote->ss_family==AF_INET) { addr=&((const sockaddr_in*)remote)->sin_addr; size=4; }
    else if(remote->ss_family==AF_INET6) {
        if(((const sockaddr_in6*)remote)->sin6_scope_id) return -1; // no scope-id field in this minimal Java ABI
        addr=&((const sockaddr_in6*)remote)->sin6_addr; size=16;
    } else return -1;
    auto a=bytes(l.e,addr,size), b=bytes(l.e,data,len); if(l.e->ExceptionCheck()) return -1;
    return l.e->CallIntMethod(c->cb,c->wire,(jlong)local,a,(jint)port(*remote),b,(jint)ttl);
}
void virtualFrame(ZT_Node*,void* user,void*,uint64_t nwid,void**,uint64_t src,uint64_t dst,unsigned type,unsigned vlan,const void* data,unsigned len) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok) return;
    auto b=bytes(l.e,data,len); if(!l.e->ExceptionCheck())
        l.e->CallVoidMethod(c->cb,c->frame,(jlong)nwid,(jlong)src,(jlong)dst,(jint)type,(jint)vlan,b);
}
int networkConfig(ZT_Node*,void* user,void*,uint64_t nwid,void**,ZT_VirtualNetworkConfigOperation op,const ZT_VirtualNetworkConfig* cfg) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok) return -1;
    auto j=javaJson(l.e,configJson(cfg)); if(l.e->ExceptionCheck()) return -1;
    return l.e->CallIntMethod(c->cb,c->config,(jlong)nwid,(jint)op,j);
}
void bigEndian(std::vector<unsigned char>& b,uint64_t n) { for(int i=7;i>=0;--i) b.push_back((n>>(8*i))&255); }
void event(ZT_Node*,void* user,void*,ZT_Event code,const void* payload) {
    auto* c=(Context*)user; Locals l(c); if(!l.ok) return;
    std::vector<unsigned char> b; bool has=false;
    if(payload && code==ZT_EVENT_TRACE) {
        const char* s=(const char*)payload; b.assign(s,s+strnlen(s,65535)); has=true;
    } else if(payload && code==ZT_EVENT_USER_MESSAGE) {
        const auto* m=(const ZT_UserMessage*)payload; bigEndian(b,m->origin); bigEndian(b,m->typeId);
        if(m->length && m->data) b.insert(b.end(),(const unsigned char*)m->data,(const unsigned char*)m->data+m->length); has=true;
    } else if(payload && code==ZT_EVENT_REMOTE_TRACE) {
        const auto* t=(const ZT_RemoteTrace*)payload; bigEndian(b,t->origin);
        if(t->len && t->data) b.insert(b.end(),(unsigned char*)t->data,(unsigned char*)t->data+t->len); has=true;
    }
    auto data=has ? bytes(l.e,b.data(),(unsigned)b.size()) : nullptr;
    if(!l.e->ExceptionCheck()) l.e->CallVoidMethod(c->cb,c->event,(jint)code,data);
}
std::vector<unsigned char> input(JNIEnv* e,jbyteArray b,unsigned limit) {
    require(b!=nullptr,"null byte array"); jsize n=e->GetArrayLength(b); require((unsigned)n<=limit,"input packet too large");
    std::vector<unsigned char> out(n); if(n) e->GetByteArrayRegion(b,0,n,(jbyte*)out.data()); return out;
}
void deadline(JNIEnv* e,jlongArray a) { require(a && e->GetArrayLength(a)>=1,"nextDeadline needs at least one element"); }
void writeDeadline(JNIEnv* e,jlongArray a,int64_t d) { if(!e->ExceptionCheck()) { jlong v=d; e->SetLongArrayRegion(a,0,1,&v); } }
void mac(jlong m) { require(((uint64_t)m>>48)==0,"MAC must fit 48 bits"); }
}

extern "C" JNIEXPORT jlong JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_nodeNew(JNIEnv* e,jclass,jobject cb,jlong now) {
 try {
    require(cb && now>=0,"callback and nonnegative clock required"); auto c=std::make_shared<Context>(); c->env=e; c->lastNow=now;
    c->cb=e->NewGlobalRef(cb); if(!c->cb) return 0;
    jclass cls=e->GetObjectClass(cb);
    c->get=e->GetMethodID(cls,"stateGet","(I[J)[B"); c->put=e->GetMethodID(cls,"statePut","(I[J[B)V");
    c->wire=e->GetMethodID(cls,"wireSend","(J[BI[BI)I"); c->frame=e->GetMethodID(cls,"frame","(JJJII[B)V");
    c->config=e->GetMethodID(cls,"networkConfig","(JILjava/lang/String;)I"); c->event=e->GetMethodID(cls,"event","(I[B)V");
    e->DeleteLocalRef(cls); if(e->ExceptionCheck()) return 0;
    ZT_Node_Callbacks callbacks{}; callbacks.version=0; callbacks.statePutFunction=putState; callbacks.stateGetFunction=getState;
    callbacks.wirePacketSendFunction=wireSend; callbacks.virtualNetworkFrameFunction=virtualFrame;
    callbacks.virtualNetworkConfigFunction=networkConfig; callbacks.eventCallback=event;
    ZT_Node_Config config{}; // explicit config argument added in current C API
    int result=ZT_Node_new(&c->node,&config,c.get(),e,&callbacks,now);
    if(result || e->ExceptionCheck()) {
        if(c->node) { ZT_Node_delete(c->node); c->node=nullptr; }
        exception(e,"java/lang/IllegalStateException","ZT_Node_new failed"); return 0;
    }
    std::lock_guard<std::mutex> lock(registryMutex); jlong handle=nextHandle++;
    registry.emplace(handle,c); c->env=nullptr; return handle;
 } END_JNI(0)
}
extern "C" JNIEXPORT void JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_nodeDelete(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h); ZT_Node_delete(x.c->node); x.c->node=nullptr; e->DeleteGlobalRef(x.c->cb); x.c->cb=nullptr;
       std::lock_guard<std::mutex> l(registryMutex); registry.erase(h);
 } END_JNI()
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_processWirePacket(JNIEnv* e,jclass,jlong h,jlong now,jlong local,jbyteArray addr,jint remotePort,jbyteArray data,jlongArray next) {
 try {
    Entry x(e,h); deadline(e,next); require(remotePort>0 && remotePort<=65535,"remote port out of range");
    auto a=input(e,addr,16), b=input(e,data,65535); require(a.size()==4 || a.size()==16,"IP address must contain 4 or 16 bytes");
    require(!b.empty(),"empty UDP packet"); sockaddr_storage remote{};
    if(a.size()==4) { auto* v=(sockaddr_in*)&remote; v->sin_family=AF_INET; v->sin_port=htons(remotePort); std::memcpy(&v->sin_addr,a.data(),4); }
    else { auto* v=(sockaddr_in6*)&remote; v->sin6_family=AF_INET6; v->sin6_port=htons(remotePort); std::memcpy(&v->sin6_addr,a.data(),16); }
    x.now(now); volatile int64_t d=now; int rc=ZT_Node_processWirePacket(x.c->node,e,now,local,&remote,b.data(),b.size(),&d); writeDeadline(e,next,d); return rc;
 } END_JNI(-1)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_processFrame(JNIEnv* e,jclass,jlong h,jlong now,jlong nwid,jlong src,jlong dst,jint type,jint vlan,jbyteArray frame,jlongArray next) {
 try {
    Entry x(e,h); deadline(e,next); mac(src); mac(dst); require(type>=0 && type<=65535,"EtherType out of range");
    require(vlan>=0 && vlan<=1023,"VLAN must fit C API 10-bit field"); auto b=input(e,frame,ZT_MAX_MTU);
    x.now(now); volatile int64_t d=now; int rc=ZT_Node_processVirtualNetworkFrame(x.c->node,e,now,(uint64_t)nwid,src,dst,type,vlan,b.data(),b.size(),&d); writeDeadline(e,next,d); return rc;
 } END_JNI(-1)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_processBackgroundTasks(JNIEnv* e,jclass,jlong h,jlong now,jlongArray next) {
 try { Entry x(e,h); deadline(e,next); x.now(now); volatile int64_t d=now; int rc=ZT_Node_processBackgroundTasks(x.c->node,e,now,&d); writeDeadline(e,next,d); return rc; } END_JNI(-1)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_join(JNIEnv* e,jclass,jlong h,jlong nwid) {
 try { Entry x(e,h); require(nwid!=0,"zero network ID"); return ZT_Node_join(x.c->node,nwid,nullptr,e); } END_JNI(-1)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_leave(JNIEnv* e,jclass,jlong h,jlong nwid) {
 try { Entry x(e,h); require(nwid!=0,"zero network ID"); return ZT_Node_leave(x.c->node,nwid,nullptr,e); } END_JNI(-1)
}
extern "C" JNIEXPORT jlong JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_address(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h); return ZT_Node_address(x.c->node); } END_JNI(0)
}
extern "C" JNIEXPORT jstring JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_statusJson(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h); ZT_NodeStatus s{}; ZT_Node_status(x.c->node,&s);
    return javaJson(e,{{"address",hex(s.address,10)},{"online",s.online!=0},{"publicIdentity",s.publicIdentity?s.publicIdentity:""}});
 } END_JNI(nullptr)
}
extern "C" JNIEXPORT jstring JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_networksJson(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h); auto* list=ZT_Node_networks(x.c->node); if(!list) throw std::bad_alloc();
    struct Free { ZT_Node* n; void* p; ~Free(){ZT_Node_freeQueryResult(n,p);} } free{x.c->node,list};
    Json j=Json::array(); for(unsigned long i=0;i<list->networkCount;++i) j.push_back(configJson(&list->networks[i])); return javaJson(e,j);
 } END_JNI(nullptr)
}
extern "C" JNIEXPORT jstring JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_peersJson(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h); auto* list=ZT_Node_peers(x.c->node); if(!list) throw std::bad_alloc();
    struct Free { ZT_Node* n; void* p; ~Free(){ZT_Node_freeQueryResult(n,p);} } free{x.c->node,list};
    Json j=Json::array(); for(unsigned long i=0;i<list->peerCount;++i) { const auto& p=list->peers[i]; Json paths=Json::array();
        for(unsigned k=0;k<p.pathCount && k<ZT_MAX_PEER_NETWORK_PATHS;++k) { const auto& a=p.paths[k];
            paths.push_back({{"address",ip(a.address)},{"port",port(a.address)},{"localSocket",a.localSocket},
                {"lastSend",a.lastSend},{"lastReceive",a.lastReceive},{"preferred",a.preferred!=0},{"expired",a.expired!=0}}); }
        j.push_back({{"address",hex(p.address,10)},{"role",p.role},{"latency",p.latency},
            {"versionMajor",p.versionMajor},{"versionMinor",p.versionMinor},{"versionRev",p.versionRev},{"paths",paths}});
    } return javaJson(e,j);
 } END_JNI(nullptr)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_multicastSubscribe(JNIEnv* e,jclass,jlong h,jlong nwid,jlong group,jlong adi) {
 try { Entry x(e,h); mac(group); require((uint64_t)adi<=0xffffffffULL,"ADI must fit unsigned 32 bits"); return ZT_Node_multicastSubscribe(x.c->node,e,nwid,group,(uint32_t)adi); } END_JNI(-1)
}
extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_multicastUnsubscribe(JNIEnv* e,jclass,jlong h,jlong nwid,jlong group,jlong adi) {
 try { Entry x(e,h); mac(group); require((uint64_t)adi<=0xffffffffULL,"ADI must fit unsigned 32 bits"); return ZT_Node_multicastUnsubscribe(x.c->node,nwid,group,(uint32_t)adi); } END_JNI(-1)
}

extern "C" JNIEXPORT jint JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_addLocalInterfaceAddress(JNIEnv* e,jclass,jlong h,jbyteArray addr,jint portValue) {
 try {
    Entry x(e,h); auto a=input(e,addr,16);
    require((a.size()==4 || a.size()==16) && portValue>0 && portValue<=65535,"invalid local address");
    sockaddr_storage local{};
    if(a.size()==4) { auto* v=(sockaddr_in*)&local;v->sin_family=AF_INET;v->sin_port=htons(portValue);std::memcpy(&v->sin_addr,a.data(),4); }
    else { auto* v=(sockaddr_in6*)&local;v->sin6_family=AF_INET6;v->sin6_port=htons(portValue);std::memcpy(&v->sin6_addr,a.data(),16); }
    return ZT_Node_addLocalInterfaceAddress(x.c->node,&local);
 } END_JNI(-1)
}
extern "C" JNIEXPORT void JNICALL Java_cat_kenny_r1_tunnels_zt_ZtNode_clearLocalInterfaceAddresses(JNIEnv* e,jclass,jlong h) {
 try { Entry x(e,h);ZT_Node_clearLocalInterfaceAddresses(x.c->node); } END_JNI()
}

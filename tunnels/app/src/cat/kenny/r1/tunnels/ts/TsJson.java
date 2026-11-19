// Copyright (c) Tailscale Inc & AUTHORS
// Copyright (c) 2026 haretic contributors
// SPDX-License-Identifier: BSD-3-Clause
package cat.kenny.r1.tunnels.ts;
import cat.kenny.r1.tunnels.Engine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Whitelist metadata out of LocalAPI; never expose the raw response or private Prefs/NetMap. */
public final class TsJson {
    private TsJson() {}
    public static String controlUrl(String url) {
        if(url==null || url.isEmpty()) return "";
        URI uri=URI.create(url);
        if(!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
        return uri.toString();
    }
    static String browseUrl(String url) { return url==null || url.isEmpty()?"":controlUrl(url); }
    public static JSONObject maskedPrefs(String control,String exit,boolean routes,boolean dns,boolean running) throws Exception {
        return new JSONObject().put("ControlURL",controlUrl(control)).put("ControlURLSet",true)
            .put("ExitNodeID",exit==null?"":exit).put("ExitNodeIDSet",true)
            .put("RouteAll",routes).put("RouteAllSet",true).put("CorpDNS",dns).put("CorpDNSSet",true)
            .put("WantRunning",running).put("WantRunningSet",true);
    }
    static List<String> strings(JSONArray a) {
        List<String> list=new ArrayList<>();if(a!=null) for(int i=0;i<a.length();i++) list.add(a.optString(i));return list;
    }
    static Engine.BackendState backend(String state) {
        switch(state) {
            case "NoState":return Engine.BackendState.NO_STATE;
            case "InUseOtherUser":return Engine.BackendState.IN_USE_OTHER_USER;
            case "NeedsLogin":return Engine.BackendState.NEEDS_LOGIN;
            case "NeedsMachineAuth":return Engine.BackendState.NEEDS_MACHINE_AUTH;
            case "Stopped":return Engine.BackendState.STOPPED;
            case "Starting":return Engine.BackendState.STARTING;
            case "Running":return Engine.BackendState.RUNNING;
            default:return Engine.BackendState.UNKNOWN;
        }
    }
    static void state(Engine.Update u,Engine.BackendState state) {
        u.backendState=state;u.error=Engine.Error.NONE;
        switch(state) {
            case RUNNING:u.state=Engine.State.RUNNING;u.loginComplete=true;u.loginUrl="";break;
            case NEEDS_LOGIN:u.state=Engine.State.AUTHENTICATING;u.loginComplete=false;break;
            case NEEDS_MACHINE_AUTH:u.state=Engine.State.WAITING_APPROVAL;break;
            case STOPPED:u.state=Engine.State.STOPPED;break;
            default:u.state=Engine.State.STARTING;
        }
    }
    public static Engine.Snapshot status(JSONObject json,Engine.Snapshot previous) {
        Engine.Update u=previous.copy();state(u,backend(json.optString("BackendState")));
        u.addresses=strings(json.optJSONArray("TailscaleIPs"));
        JSONObject self=json.optJSONObject("Self"),tailnet=json.optJSONObject("CurrentTailnet");
        u.nodeAddress=self==null?"":self.optString("ID");u.online=self!=null && self.optBoolean("Online");
        u.networkName=tailnet==null?"":tailnet.optString("Name");
        u.hostName=self==null?"":self.optString("HostName");
        if(u.hostName.isEmpty() && self!=null) u.hostName=self.optString("DNSName");
        JSONObject users=json.optJSONObject("User");
        JSONObject user=users==null || self==null?null:users.optJSONObject(self.optString("UserID"));
        u.loginName=user==null?"":user.optString("LoginName");
        String auth=json.optString("AuthURL");if(!auth.isEmpty() && !u.loginComplete) u.loginUrl=browseUrl(auth);
        u.peers=new ArrayList<>();u.relayed=false;
        JSONObject peers=json.optJSONObject("Peer");
        if(peers!=null) for(Iterator<String> i=peers.keys();i.hasNext();) {
            JSONObject peer=peers.optJSONObject(i.next());if(peer==null) continue;
            boolean online=peer.optBoolean("Online");
            Engine.Path path=!online?Engine.Path.UNREACHABLE:!peer.optString("CurAddr").isEmpty()?Engine.Path.DIRECT
                :!peer.optString("Relay").isEmpty()?Engine.Path.RELAY:Engine.Path.UNREACHABLE;
            u.relayed|=path==Engine.Path.RELAY;
            u.peers.add(new Engine.Peer(peer.optString("ID"),peer.optString("DNSName",peer.optString("HostName")),strings(peer.optJSONArray("TailscaleIPs")),path,-1,peer.optBoolean("ExitNodeOption")));
        }
        return u.build();
    }
    public static Engine.Snapshot notification(JSONObject json,Engine.Snapshot previous) {
        Engine.Update u=previous.copy();
        // Go's Notify pointer fields are emitted as explicit JSON nulls. They mean
        // "no update", not an error, empty login event, or an unknown backend state.
        if(!json.isNull("State")) {
            String[] names={"NoState","InUseOtherUser","NeedsLogin","NeedsMachineAuth","Stopped","Starting","Running"};
            int state=json.optInt("State",-1);state(u,state>=0 && state<names.length?backend(names[state]):Engine.BackendState.UNKNOWN);
        }
        if(!json.isNull("ErrMessage") && !json.optString("ErrMessage").isEmpty()) u.error=Engine.Error.API;
        if(!json.isNull("BrowseToURL")) {u.loginUrl=browseUrl(json.optString("BrowseToURL"));u.loginComplete=false;}
        if(!json.isNull("LoginFinished")) {u.loginComplete=true;u.loginUrl="";}
        JSONObject engine=json.optJSONObject("Engine");
        if(engine!=null) {u.rx=engine.optLong("RBytes");u.tx=engine.optLong("WBytes");}
        JSONObject map=json.optJSONObject("NetMap");
        if(map!=null) {
            JSONObject self=map.optJSONObject("SelfNode");if(self!=null) {u.nodeAddress=self.optString("StableID");u.addresses=strings(self.optJSONArray("Addresses"));}
            u.networkName=map.optString("Domain",u.networkName);
        }
        return u.build();
    }
}

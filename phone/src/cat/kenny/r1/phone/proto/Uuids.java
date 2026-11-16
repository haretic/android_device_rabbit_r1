// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone.proto;

import java.util.UUID;

/** Apple's public GATT identifiers, in canonical UUID byte order. */
public final class Uuids {
    public static final UUID ANCS_SERVICE = uuid("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    public static final UUID ANCS_NOTIFICATION_SOURCE =
            uuid("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    public static final UUID ANCS_CONTROL_POINT = uuid("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    public static final UUID ANCS_DATA_SOURCE = uuid("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");
    public static final UUID AMS_SERVICE = uuid("89D3502B-0F36-433A-8EF4-C502AD55F8DC");
    public static final UUID AMS_REMOTE_COMMAND = uuid("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2");
    public static final UUID AMS_ENTITY_UPDATE = uuid("2F7CABCE-808D-411F-9A0C-BB92BA96C102");
    public static final UUID AMS_ENTITY_ATTRIBUTE = uuid("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7");

    private Uuids() { }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}

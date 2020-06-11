package org.edu_sharing.elasticsearch.tools;

import org.apache.commons.codec.binary.Base64;

import java.nio.charset.StandardCharsets;

public class Tools {

    public static String getBasicAuth(String user, String password){
        String auth = user + ":" + password;
        byte[] encodedAuth = Base64.encodeBase64(
                auth.getBytes(StandardCharsets.ISO_8859_1));
        String authHeader = "Basic " + new String(encodedAuth);
        return authHeader;
    }

    public static String getStoreRef(String nodeRef){
        return getProtocol(nodeRef) + "://" + getIdentifier(nodeRef);
    }

    public static String getProtocol(String nodeRef){
        return nodeRef.split("://")[0];
    }

    public static String getIdentifier(String nodeRef){
        return nodeRef.split("://")[1].split("/")[0];
    }

}
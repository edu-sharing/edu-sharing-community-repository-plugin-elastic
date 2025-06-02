package org.edu_sharing.elasticsearch.tools;

import org.apache.commons.codec.binary.Base64;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;

public class Tools {

    public static final DecimalFormat df = new DecimalFormat("0.00");

    public static final long WAIT_FOR_SERVICE = 10000;

    public static String getBasicAuth(String user, String password){
        String auth = user + ":" + password;
        byte[] encodedAuth = Base64.encodeBase64(
                auth.getBytes(StandardCharsets.ISO_8859_1));
        return "Basic " + new String(encodedAuth);
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

    public static String getUUID(String nodeRef){
        return nodeRef.split("://")[1].split("([/|])")[1];
    }

    public static void main(String[] args) {
        String nodeRef="workspace://SpacesStore/6738aac6-094b-438a-82b5-661ed6b4650b";
        System.out.println("prot:"+Tools.getProtocol(nodeRef));
        System.out.println("storeref:"+Tools.getStoreRef(nodeRef));
        System.out.println("store identifier:"+Tools.getIdentifier(nodeRef));
        System.out.println("uuid:"+Tools.getUUID(nodeRef));
    }

}

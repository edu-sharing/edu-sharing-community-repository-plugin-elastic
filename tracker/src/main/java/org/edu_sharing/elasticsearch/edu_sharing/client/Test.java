package org.edu_sharing.elasticsearch.edu_sharing.client;

import org.edu_sharing.elasticsearch.tools.Tools;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

public class Test {

    static String HOST = "http://localhost:8080";

   // static String HOST = "https://redaktion.openeduhub.net";
    static  String URL_MDS_VALUES = HOST + "/edu-sharing/rest/mds/v1/metadatasets/-home-/${mds}/values";
    static String URL_MDS = HOST + "/edu-sharing/rest/mds/v1/metadatasets/-home-/${mds}";

    String user = "admin";
    String password = "admin";
    Client client = ClientBuilder.newBuilder().build();

    public static void main(String[] args) {


        new Test().getMDSValues();
    }

    public void getMDSValues(){
        String mds ="mds";
        String property = "ccm:educationalcontext";
        //String property = "license";
        //String property = "ccm:educationallearningresourcetype";
        String language = "de_DE";
        //String language = "en_US";





        GetValuesParameters params = new GetValuesParameters();
        GetValuesParameters.ValueParameters vp = new GetValuesParameters.ValueParameters();

        String url = URL_MDS_VALUES;
        url = url.replace("${mds}",mds);


        vp.setProperty(property);
        vp.setQuery("ngsearch");
        params.setValueParameters(vp);

        ValuespaceEntries entries = client
                .target(url)
                .request(MediaType.APPLICATION_JSON)
                .header("Accept-Language",language)
                .header("locale", language)
                .header(HttpHeaders.AUTHORIZATION, Tools.getBasicAuth(user, password))
                .post(Entity.json(params)).readEntity(ValuespaceEntries.class);


        for(ValuespaceEntry e : entries.getValues()){
            System.out.println("k: " + e.getKey() + " v: " + e.getDisplayString());
        }
    }

    public void getValuespaceProperties(){
        String url = URL_MDS;
        url = url.replace("${mds}","-default-");

        MdsV2 mdsV2 = client
                .target(url)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, Tools.getBasicAuth(user,password))
                .get().readEntity(MdsV2.class);

        List<String> result = new ArrayList<>();
        for(WidgetV2 widget : mdsV2.getWidgets()){
            if(widget.isHasValues()){
                result.add(widget.getId());
            }
        }

        for(String s:result){
            System.out.println(s);
        }
    }
}

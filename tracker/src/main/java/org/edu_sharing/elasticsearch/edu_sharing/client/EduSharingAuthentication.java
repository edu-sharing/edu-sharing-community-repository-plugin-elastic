package org.edu_sharing.elasticsearch.edu_sharing.client;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.HttpURLConnection;
import java.util.Date;

@Aspect
@Component
public class EduSharingAuthentication {

    Logger logger = LoggerFactory.getLogger(EduSharingAuthentication.class);

    @Lazy
    @Autowired
    private EduSharingClient eduSharingClient;

    long lastTimeAuthChecked = 0;

    @Value("${edusharing.sessionvalidate.delay}")
    long eduSharingSessionValidateDelay;

    @Before("@annotation(org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingAuthentication.ManageAuthentication)")
    public void manageAuthentication(JoinPoint joinPoint) {
        Date d = new Date();
        if(lastTimeAuthChecked == 0 || (d.getTime() - lastTimeAuthChecked) > eduSharingSessionValidateDelay) {
            logger.debug("manageAuthentication for:" + joinPoint.getSignature().getName());
            eduSharingClient.manageAuthentication();
            lastTimeAuthChecked = d.getTime();
        }
    }


    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ManageAuthentication {

    }

    @Component
    public class EduSharingAuthenticationResponseFilter implements ClientResponseFilter{

        /**
         * set by ApiAuthenticationFilter on every response, tells us whether the
         * request was answered as a real authenticated user ("true") or fell
         * back to guest ("false") - even when the status is 200/403/... so the
         * status code alone cannot reveal it.
         */
        private static final String HEADER_AUTHENTICATED = "X-Edu-Authenticated";

        @Autowired
        EduSharingAuthentication auth;

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            boolean authenticated = !"false".equalsIgnoreCase(responseContext.getHeaderString(HEADER_AUTHENTICATED));
            if(responseContext.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED || !authenticated){
                //session died server-side (e.g. repo restart) and fell back to guest
                //force reauth
                logger.info("got status {} authenticated header={} -> force authentication",
                        responseContext.getStatus(), responseContext.getHeaderString(HEADER_AUTHENTICATED));
                auth.lastTimeAuthChecked = 0;
            }
        }
    }

}



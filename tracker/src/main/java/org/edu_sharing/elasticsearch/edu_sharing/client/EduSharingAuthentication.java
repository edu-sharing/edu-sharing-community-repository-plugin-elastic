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

import jakarta.ws.rs.core.Response;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Date;

@Aspect
@Component
public class EduSharingAuthentication {

    Logger logger = LoggerFactory.getLogger(EduSharingAuthentication.class);

    @Lazy
    @Autowired
    private EduSharingClient eduSharingClient;

    volatile long lastTimeAuthChecked = 0;

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

    /**
     * set by the repository's ApiAuthenticationFilter on every /rest/* response: tells us whether
     * the request was answered as the real tracker user ("true") or fell back to guest ("false").
     * A guest may still get a 200 with a reduced result set, so the status code alone cannot
     * reveal it. Absent on repositories older than the commit that introduced the header - in
     * that case we simply keep relying on the periodic validateSession() check above.
     */
    public static final String HEADER_AUTHENTICATED = "X-Edu-Authenticated";

    /**
     * Reacts to a response that shows our session is no longer the tracker user: after a repository
     * restart the GuestFilter silently answers a stale JSESSIONID as guest, so admin-only endpoints
     * start returning 403 (and non-admin ones a reduced 200) instead of failing outright.
     * The periodic check in {@link #manageAuthentication(JoinPoint)} repairs this too, but only after
     * edusharing.sessionvalidate.delay has elapsed - this repairs it right away.
     * <p>
     * Only meant to be called from call sites that actually need an authenticated session; it is
     * deliberately not a global ClientResponseFilter, since it must not fire on authless endpoints
     * (_about), on the preview servlet, or on the authenticate() call itself.
     *
     * @return true when the session had been lost and has been renewed, so the caller should give
     * up on this response and retry on its next run
     */
    public boolean recoverIfSessionLost(Response response) {
        if (!"false".equalsIgnoreCase(response.getHeaderString(HEADER_AUTHENTICATED))) {
            return false;
        }
        // authenticate() replaces the shared jsessionId and toggles client-wide redirect
        // properties, so let only one tracker thread at a time run through it
        synchronized (this) {
            logger.info("edu-sharing answered status {} with {}=false: our session fell back to guest, re-authenticating now",
                    response.getStatus(), HEADER_AUTHENTICATED);
            eduSharingClient.authenticate();
            lastTimeAuthChecked = System.currentTimeMillis();
        }
        return true;
    }

}



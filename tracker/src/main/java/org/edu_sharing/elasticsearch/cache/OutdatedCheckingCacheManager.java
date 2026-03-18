package org.edu_sharing.elasticsearch.cache;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.AboutApi;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.About;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class OutdatedCheckingCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final AboutApi aboutApi;
    private final long checkAfterMillis;

    private final ConcurrentMap<String, Cache> wrappedCaches = new ConcurrentHashMap<>();

    private final AtomicBoolean invalidationCheckRunning = new AtomicBoolean(false);
    private volatile long lastSeenCacheUpdate = 0L;
    private volatile long lastCheckTime = 0L;

    @Override
    public Cache getCache(@NonNull String name) {
        Cache cache = delegate.getCache(name);
        if (cache == null) {
            return null;
        }
        return wrappedCaches.computeIfAbsent(name, n -> new OutdatedCheckingCache(cache));
    }

    private void checkAndInvalidateAllIfOutdated() {

        if (!invalidationCheckRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            long now = System.currentTimeMillis();
            if (now - lastCheckTime < checkAfterMillis) {
                return;
            }

            About about = aboutApi.about().block();
            lastCheckTime = System.currentTimeMillis();

            Long lastCacheUpdate = about != null ? about.getLastCacheUpdate() : null;
            if (lastCacheUpdate == null || lastCacheUpdate <= lastSeenCacheUpdate) {
                return;
            }

            for (String cacheName : delegate.getCacheNames()) {
                Cache cache = delegate.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            }

            lastSeenCacheUpdate = lastCacheUpdate;
        } finally {
            invalidationCheckRunning.set(false);
        }
    }

    @Override
    public @NonNull Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    private class OutdatedCheckingCache implements Cache {
        private final Cache target;

        private OutdatedCheckingCache(Cache target) {
            this.target = target;
        }

        @Override
        public @NonNull String getName() {
            return target.getName();
        }

        @Override
        public @NonNull Object getNativeCache() {
            return target.getNativeCache();
        }

        @Override
        public ValueWrapper get(@NonNull Object key) {
            checkAndInvalidateAllIfOutdated();
            return target.get(key);
        }

        @Override
        public <T> T get(@NonNull Object key, Class<T> type) {
            checkAndInvalidateAllIfOutdated();
            return target.get(key, type);
        }

        @Override
        public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
            checkAndInvalidateAllIfOutdated();
            return target.get(key, valueLoader);
        }

        @Override
        public void put(@NonNull Object key, Object value) {
            target.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(@NonNull Object key, Object value) {
            return target.putIfAbsent(key, value);
        }

        @Override
        public void evict(@NonNull Object key) {
            target.evict(key);
        }

        @Override
        public boolean evictIfPresent(@NonNull Object key) {
            return target.evictIfPresent(key);
        }

        @Override
        public void clear() {
            target.clear();
        }
    }
}
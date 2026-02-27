package org.edu_sharing.elasticsearch.tracker.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.function.ThrowingConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ThreadUtil {

    @Getter
    protected ForkJoinPool threadPool;

    public ThreadUtil(Integer threadCount) {
        this.threadPool = new ForkJoinPool(threadCount);
    }


    public <T> void runThreaded(List<T> data, ThrowingConsumer<T> worker, boolean throwOnTimeout, boolean reThrow) throws IOException {
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for(T d : data){
            threadPool.execute(() -> {
                try{
                    worker.acceptWithException(d);
                }catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            String msg = "Fatal error while processing data: timeout";
            log.error(msg);
            if(throwOnTimeout) throw new RuntimeException(msg);
        }
        if(!errors.isEmpty()){
            log.error("Fatal error while processing data: {}", errors);
            if(reThrow){
                if(errors.get(0) instanceof IOException){
                    throw (IOException) errors.get(0);
                }else{
                    throw new RuntimeException(errors.get(0));
                }
            }
        }
    }
}

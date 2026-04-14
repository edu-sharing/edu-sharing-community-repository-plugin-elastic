package org.edu_sharing.elasticsearch.tracker.utils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Partition {

    public static <T> Collection<List<T>> getPartitions(Collection<T> collection, int partitionSize){
        return getPartitionInternal(collection,partitionSize).values();
    }

    private static <T> Map<Integer, List<T>> getPartitionInternal(Collection<T> collection, int partitionSize){
        final AtomicInteger counter = new AtomicInteger(0);
        return collection.stream()
                .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / partitionSize));
    }
}

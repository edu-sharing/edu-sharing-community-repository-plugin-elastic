package org.edu_sharing.elasticsearch.tracker.core;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.NonNull;

@Component
@RequiredArgsConstructor
public class TrackerBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (bean instanceof TrackerScheduleConfig<?, ?> tracker) {
            if (tracker.getName() == null) {
                tracker.setName(beanName);
            }
        }
        return bean;
    }

}

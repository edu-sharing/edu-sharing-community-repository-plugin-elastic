package org.edu_sharing.elasticsearch.tracker.core;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrackerBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
        if (bean instanceof TrackerScheduleConfig<?, ?> tracker) {
            if (tracker.getName() == null) {
                tracker.setName(beanName);
            }
        }
        return bean;
    }

}

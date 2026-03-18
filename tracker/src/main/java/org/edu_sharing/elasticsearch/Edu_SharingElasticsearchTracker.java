package org.edu_sharing.elasticsearch;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.edu_sharing.elasticsearch.elasticsearch.config.AutoConfigurationTracker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import picocli.CommandLine;

@EnableCaching
@EnableScheduling
@SpringBootApplication
@RequiredArgsConstructor
@Import(AutoConfigurationTracker.class)
public class Edu_SharingElasticsearchTracker implements CommandLineRunner, ApplicationContextAware {

    private final CLI cli;

    @Setter
    private ApplicationContext applicationContext;

    public static void main(String[] args) {
        SpringApplication.run(Edu_SharingElasticsearchTracker.class, args);
    }

    @Override
    public void run(String... args) {
        CommandLine cli = new CommandLine(this.cli);
        cli.setUnmatchedArgumentsAllowed(true);
        if(cli.execute(args) == 0){
            System.out.println(applicationContext);
            SpringApplication.exit(applicationContext);
            System.exit(0);
        }
    }
}

package org.edu_sharing.elasticsearch.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FilenameFilter;

@Configuration
class ScriptLoaderConfiguration {
    static Logger logger = LogManager.getLogger(ScriptLoaderConfiguration.class);

    @Value("${scriptExecutor.scriptsLocation}")
    private String scriptLocation;

    private final FilenameFilter filter = (file, s) -> s.toLowerCase().endsWith(".groovy");
    @Bean
//    @Profile("default & !debug")
    ScriptLoaderService realPathService() {
        logger.info("Getting scripts by location: " + scriptLocation);
        return new ScriptLoaderService(
                new File(scriptLocation).listFiles(filter)
        );
    }

//    @Bean
//    @Profile("debug")
//    ScriptLoaderService jarPathService() throws IOException {
//        logger.info("Getting scripts from spring resources folder");
//        return new ScriptLoaderService(
//                new ClassPathResource("scripts").getFile().listFiles(filter)
//        );
//    }


    public record ScriptLoaderService(File[] files) {
    }

}
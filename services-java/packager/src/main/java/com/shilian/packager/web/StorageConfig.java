package com.shilian.packager.web;

import com.shilian.packager.storage.InMemoryStorage;
import com.shilian.packager.storage.LocalStorage;
import com.shilian.packager.storage.Storage;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 存储装配：默认本地临时目录，可切内存实现；生产对象存储实现接入后在此替换。 */
@Configuration
public class StorageConfig {

    @Bean
    public Storage storage(
            @Value("${packager.storage.type:local}") String type,
            @Value("${packager.storage.root}") String root,
            @Value("${packager.storage.out-dir}") String outDir) {
        if ("memory".equalsIgnoreCase(type)) {
            return new InMemoryStorage();
        }
        return new LocalStorage(Path.of(root), Path.of(outDir));
    }
}

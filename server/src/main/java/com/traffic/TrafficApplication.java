package com.traffic;

import com.traffic.config.AnomalyProperties;
import com.traffic.config.MediamtxProperties;
import com.traffic.config.MockInferenceProperties;
import com.traffic.config.ParkingProperties;
import com.traffic.config.StreamProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.traffic.mapper")
@EnableScheduling
@EnableConfigurationProperties({
        StreamProperties.class,
        MediamtxProperties.class,
        MockInferenceProperties.class,
        AnomalyProperties.class,
        ParkingProperties.class
})
public class TrafficApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrafficApplication.class, args);
    }
}

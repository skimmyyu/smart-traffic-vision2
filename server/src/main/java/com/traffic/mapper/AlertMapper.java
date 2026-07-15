package com.traffic.mapper;

import com.traffic.entity.Alert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AlertMapper {

    List<Alert> findRecent(@Param("limit") int limit);

    int countToday();

    int countTodayByType(@Param("alertType") String alertType);

    int insert(Alert alert);
}

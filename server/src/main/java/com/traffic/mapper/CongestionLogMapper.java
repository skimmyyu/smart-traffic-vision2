package com.traffic.mapper;

import com.traffic.entity.CongestionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CongestionLogMapper {

    List<CongestionLog> findRecent(@Param("limit") int limit);

    CongestionLog findLatest();

    Double avgVehicleCountToday();

    int insert(CongestionLog log);
}

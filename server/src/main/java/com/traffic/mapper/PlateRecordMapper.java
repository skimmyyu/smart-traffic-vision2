package com.traffic.mapper;

import com.traffic.entity.PlateRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlateRecordMapper {

    List<PlateRecord> findRecent(@Param("limit") int limit);

    List<PlateRecord> findSince(@Param("since") LocalDateTime since);

    int countToday();

    int insert(PlateRecord record);

    PlateRecord findLatestByPlate(@Param("plateNumber") String plateNumber);
}

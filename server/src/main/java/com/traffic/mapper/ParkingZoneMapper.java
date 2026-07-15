package com.traffic.mapper;

import com.traffic.entity.ParkingZone;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ParkingZoneMapper {

    List<ParkingZone> findByChannel(@Param("channelId") String channelId);

    List<ParkingZone> findEnabledByChannel(@Param("channelId") String channelId);

    ParkingZone findById(@Param("id") Long id);

    int insert(ParkingZone zone);

    int update(ParkingZone zone);

    int deleteById(@Param("id") Long id);
}

package com.traffic.mapper;

import com.traffic.entity.CameraRoadRoi;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CameraRoadRoiMapper {

    List<CameraRoadRoi> findByChannel(@Param("channelId") String channelId);

    List<CameraRoadRoi> findEnabledByChannel(@Param("channelId") String channelId);

    List<CameraRoadRoi> findAll();

    CameraRoadRoi findById(@Param("id") Long id);

    int insert(CameraRoadRoi roi);

    int update(CameraRoadRoi roi);

    int deleteById(@Param("id") Long id);
}

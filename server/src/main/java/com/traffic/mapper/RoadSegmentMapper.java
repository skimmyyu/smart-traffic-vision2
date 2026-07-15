package com.traffic.mapper;

import com.traffic.entity.RoadSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoadSegmentMapper {

    List<RoadSegment> findAll();

    List<RoadSegment> findEnabled();

    RoadSegment findById(@Param("id") Long id);

    int insert(RoadSegment segment);

    int update(RoadSegment segment);

    int deleteById(@Param("id") Long id);
}

package com.traffic.mapper;

import com.traffic.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DeviceMapper {

    List<Device> findAll();

    Device findById(Long id);

    Device findByName(String name);

    int insert(Device device);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("lastOnline") LocalDateTime lastOnline);

    int markOfflineBefore(@Param("deadline") LocalDateTime deadline);

    int deleteById(@Param("id") Long id);

    int updateName(@Param("id") Long id, @Param("name") String name);
}

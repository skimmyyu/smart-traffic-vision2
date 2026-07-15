package com.traffic.mapper;

import com.traffic.entity.Whitelist;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WhitelistMapper {

    List<Whitelist> findAll();

    Whitelist findByPlateNumber(String plateNumber);

    int insert(Whitelist whitelist);

    int deleteByPlateNumber(String plateNumber);
}

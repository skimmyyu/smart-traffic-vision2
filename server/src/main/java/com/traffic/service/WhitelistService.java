package com.traffic.service;

import com.traffic.dto.WhitelistCreateRequest;
import com.traffic.entity.Whitelist;
import com.traffic.mapper.WhitelistMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class WhitelistService {

    private final WhitelistMapper whitelistMapper;

    public WhitelistService(WhitelistMapper whitelistMapper) {
        this.whitelistMapper = whitelistMapper;
    }

    public List<Whitelist> listAll() {
        return whitelistMapper.findAll();
    }

    public Whitelist add(WhitelistCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getPlateNumber())) {
            throw new IllegalArgumentException("车牌号码不能为空");
        }

        String plateNumber = request.getPlateNumber().trim().toUpperCase();
        if (whitelistMapper.findByPlateNumber(plateNumber) != null) {
            throw new DuplicateKeyException("车牌已存在于白名单");
        }

        Whitelist whitelist = new Whitelist();
        whitelist.setPlateNumber(plateNumber);
        whitelistMapper.insert(whitelist);
        return whitelistMapper.findByPlateNumber(plateNumber);
    }

    public void remove(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            throw new IllegalArgumentException("车牌号码不能为空");
        }
        int rows = whitelistMapper.deleteByPlateNumber(plateNumber.trim());
        if (rows == 0) {
            throw new IllegalArgumentException("白名单中不存在该车牌");
        }
    }

    public boolean contains(String plateNumber) {
        return StringUtils.hasText(plateNumber)
                && whitelistMapper.findByPlateNumber(plateNumber.trim().toUpperCase()) != null;
    }
}

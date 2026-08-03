package com.schoolwork.epsys.acl.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolwork.epsys.acl.service.DevicetousersService;
import com.schoolwork.epsys.common.Result;
import com.schoolwork.epsys.device.client.DeviceFeignClient;
import com.schoolwork.epsys.model.acl.Devicetousers;
import com.schoolwork.epsys.model.device.MaintainRecord;
import com.schoolwork.epsys.utils.WebUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.misc.Hash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备领用关系接口，负责用户与设备的绑定、解绑和关系查询。
 */
@RestController
@RequestMapping("/deviceToUser")
public class DeviceToUserController {


    @Autowired
    private DevicetousersService deviceToUserService;

    @Autowired
    DeviceFeignClient deviceFeignClient;

    /**
     * 为用户分配设备，并在绑定成功后将设备状态更新为“使用”。
     */
    @RequestMapping("/addDeviceToUser")
    public void addDeviceToUser(HttpServletRequest req,HttpServletResponse resp,
                                   @RequestParam Integer userId,@RequestParam Integer deviceId) {
        Devicetousers deviceToUser = new Devicetousers();
        deviceToUser.setUserId(userId);
        deviceToUser.setDeviceId(deviceId);
        Boolean flag= deviceToUserService.save(deviceToUser);
        if(flag){
            deviceFeignClient.updateDeviceInstanceStatus(deviceId, "使用");
        }
        Map data=new HashMap();
        data.put("flag",flag);
        Result result= Result.ok(data);
        WebUtil.writeJson(resp, result);

    }

    /**
     * 解除用户与设备的绑定，并在解绑成功后将设备状态恢复为“闲置”。
     */
    @RequestMapping("/removeDeviceFromUser")
    public void removeDeviceFromUser(HttpServletRequest req,HttpServletResponse resp,
                                        @RequestParam Integer userId,@RequestParam  Integer deviceId) {
        Devicetousers deviceToUser = new Devicetousers();
        QueryWrapper<Devicetousers>queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq("device_id", deviceId);
        Boolean flag= deviceToUserService.remove(queryWrapper);
        if(flag){
            deviceFeignClient.updateDeviceInstanceStatus(deviceId, "闲置");
        }
        Map data=new HashMap();
        data.put("flag",flag);
        Result result= Result.ok(data);
        WebUtil.writeJson(resp, result);

    }

    /**
     * 按用户 ID 分页查询其设备领用关系。
     */
    @RequestMapping("/getDevicesByUserId")
    public void getDevicesByUserId(HttpServletRequest req,HttpServletResponse resp,@RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                   @RequestParam(value = "pageSize", defaultValue = "3") int pageSize,
                                               @RequestParam Integer userId) {
        QueryWrapper<Devicetousers>queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        Page<Devicetousers> page = new Page<>(pageNum, pageSize);
        deviceToUserService.page(page, queryWrapper);
        List<Devicetousers> deviceToUsers = page.getRecords();
        Map<String,Object> data=new HashMap();
        data.put("items",deviceToUsers);
        data.put("total", page.getTotal());
        Result result= Result.ok(data);
        WebUtil.writeJson(resp, result);

    }

}

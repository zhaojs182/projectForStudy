package com.schoolwork.epsys.acl.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolwork.epsys.acl.service.DevicetousersService;
import com.schoolwork.epsys.common.Result;
import com.schoolwork.epsys.device.client.DeviceFeignClient;
import com.schoolwork.epsys.model.acl.Devicetousers;
import com.schoolwork.epsys.utils.WebUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        QueryWrapper<Devicetousers> relationQuery = new QueryWrapper<>();
        relationQuery.eq("user_id", userId).eq("device_id", deviceId);
        if (deviceToUserService.count(relationQuery) > 0) {
            writeBindResult(resp, true, true, "该设备已在我的设备中");
            return;
        }
        QueryWrapper<Devicetousers> deviceQuery = new QueryWrapper<>();
        deviceQuery.eq("device_id", deviceId);
        if (deviceToUserService.count(deviceQuery) > 0) {
            writeBindResult(resp, false, false, "该设备已被其他用户领用");
            return;
        }

        Devicetousers deviceToUser = new Devicetousers();
        deviceToUser.setUserId(userId);
        deviceToUser.setDeviceId(deviceId);
        try {
            boolean bound = deviceToUserService.save(deviceToUser);
            if (bound) {
                deviceFeignClient.updateDeviceInstanceStatus(deviceId, "使用");
                writeBindResult(resp, true, false, "设备领用成功");
            } else {
                writeBindResult(resp, false, false, "设备领用失败");
            }
        } catch (DuplicateKeyException ignored) {
            // 并发重复提交仍按幂等成功处理，避免唯一索引冲突变成 500。
            writeBindResult(resp, true, true, "该设备已在我的设备中");
        }
    }

    private void writeBindResult(HttpServletResponse resp, boolean flag, boolean alreadyBound, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("flag", flag);
        data.put("alreadyBound", alreadyBound);
        WebUtil.writeJson(resp, Result.ok(data).message(message));
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

    /**
     * 判断指定设备是否已绑定到指定用户，供报修权属校验使用。
     */
    @RequestMapping("/isDeviceOwnedByUser")
    public Boolean isDeviceOwnedByUser(@RequestParam Integer userId, @RequestParam Integer deviceId) {
        QueryWrapper<Devicetousers> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("device_id", deviceId);
        return deviceToUserService.count(queryWrapper) > 0;
    }

}

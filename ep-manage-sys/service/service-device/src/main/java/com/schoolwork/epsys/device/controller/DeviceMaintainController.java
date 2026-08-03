package com.schoolwork.epsys.device.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolwork.epsys.acl.client.AclFeignClient;
import com.schoolwork.epsys.common.Result;
import com.schoolwork.epsys.device.mapper.MaintainRecordMapper;
import com.schoolwork.epsys.device.service.ClaimOrderConflictException;
import com.schoolwork.epsys.device.service.ClaimOrderResult;
import com.schoolwork.epsys.device.service.MaintainRecordService;
import com.schoolwork.epsys.message.client.MessageFeignClient;
import com.schoolwork.epsys.model.device.Devicemodel;
import com.schoolwork.epsys.model.device.MaintainRecord;
import com.schoolwork.epsys.model.shared.UserNotification;
import com.schoolwork.epsys.utils.WebUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备维护工单接口，负责报修、审批、抢单、进度查询和维修完成处理。
 */
@RestController
@RequestMapping("/deviceMaintain")
public class DeviceMaintainController {

    @Autowired
    private MaintainRecordService maintainRecordService;

    @Autowired
    private MaintainRecordMapper maintainRecordMapper;

    @Autowired
    MessageFeignClient messageFeignClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AclFeignClient aclFeignClient;

    @Autowired
    private RedissonClient redissonClient;




    /**
     * 维修人员抢领维护工单，通过 Redisson、幂等键、唯一领取记录和乐观锁避免重复抢单。
     */
    @PostMapping("/getMaintainOrder")
    public void getMaintainOrder(HttpServletRequest req, HttpServletResponse resp,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody MaintainRecord maintainRecord) {
        if (maintainRecord == null || maintainRecord.getId() == null || maintainRecord.getMiantainId() == null) {
            WebUtil.writeJson(resp, Result.error("工单 ID 和维修人员 ID 不能为空！"));
            return;
        }



        String requestId = resolveIdempotencyKey(
                idempotencyKey, maintainRecord.getId(), maintainRecord.getMiantainId());
        if (requestId.length() > 64) {
            WebUtil.writeJson(resp, Result.error("Idempotency-Key 长度不能超过 64 个字符！"));
            return;
        }

        // 同一工单在所有服务实例中使用相同的锁 key。
        String lockKey = "order:lock:" + maintainRecord.getId();
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;
        Result result = Result.error("抢单失败，请稍后重试！");

        try {
            // 抢单请求直接竞争锁，不排队等待；未指定 leaseTime 时由 Redisson watchdog 自动续期。
            isLocked = lock.tryLock();

            if (isLocked) {
                ClaimOrderResult claimResult = maintainRecordService.claimOrder(
                        maintainRecord.getId(), maintainRecord.getMiantainId(), requestId);
                result = buildClaimResult(claimResult);
            } else {
                result = Result.error("该工单正在被其他维修人员处理，请刷新后重试！");
            }

        } catch (DuplicateKeyException e) {
            result = Result.error("工单已被领取或幂等键已被使用，请刷新后重试！");
        } catch (Exception e) {
            e.printStackTrace();
            result = Result.error("系统异常：" + e.getMessage());
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            WebUtil.writeJson(resp, result);
        }
    }

    private String resolveIdempotencyKey(String idempotencyKey, Integer orderId, Integer repairmanId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "claim:" + orderId + ":" + repairmanId;
        }
        return idempotencyKey.trim();
    }

    private Result buildClaimResult(ClaimOrderResult claimResult) {
        return switch (claimResult) {
            case SUCCESS -> Result.ok("获取订单成功！");
            case IDEMPOTENT_SUCCESS -> Result.ok("获取订单成功！");
            case ORDER_NOT_FOUND -> Result.error("未找到对应的工单！");
            case ORDER_NOT_CLAIMABLE -> Result.error("工单当前状态不可领取！");
            case ORDER_ALREADY_CLAIMED -> Result.error("工单已被其他维修人员领取，请刷新后重试！");
            case IDEMPOTENCY_KEY_CONFLICT -> Result.error("Idempotency-Key 已被其他抢单请求使用！");
        };
    }




    /**
     * 分页查询全部设备维护工单，供管理端查看和审批。
     */
    @RequestMapping("/getMaintainRecord")
    public  void getMaintainRecord(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                   @RequestParam(value = "pageSize", defaultValue = "3") int pageSize) {
        // 创建分页对象
        Page<MaintainRecord> page = new Page<>(pageNum, pageSize);
        maintainRecordMapper.selectPage(page, null);
        List<MaintainRecord> records = page.getRecords();

        Map data = new HashMap();
        data.put("records", records);
        data.put("total", page.getTotal());
        Result result =Result.ok(data);
        WebUtil.writeJson(resp, result);

    }


    /**
     * 根据工单 ID 查询单条维护记录详情。
     */
    @RequestMapping("/getMaintainRecordById")
    public  void getMaintainRecordById(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestParam(value = "id") int id) {
        MaintainRecord maintainRecord = maintainRecordMapper.selectById(id);
        Map data = new HashMap();
        data.put("record", maintainRecord);
        Result result =Result.ok(data);
        WebUtil.writeJson(resp, result);
    }

    /**
     * 创建维护工单，并在创建成功后通知在线审批人员刷新待办列表。
     */
    @RequestMapping("/createMaintainRecord")
    public void createMaintainRecord(HttpServletRequest req, HttpServletResponse resp,
                                     @RequestBody MaintainRecord maintainRecord) {
        System.out.println("maintainRecord=" + maintainRecord);

        // 设置开始时间
        maintainRecord.setStartTime(new Date());

        // 保存维护记录
        boolean isCreated = maintainRecordService.save(maintainRecord);

        if (isCreated) {
            // 发送广播通知管理员
            try {
                System.out.println("发送广播通知管理员");
                messageFeignClient.sendToTopic4Maintain();  // Feign调用
                Result result = Result.ok("维护记录创建成功，消息已广播");
                WebUtil.writeJson(resp, result);
            } catch (Exception e) {
                e.printStackTrace();
                Result result = Result.ok("维护记录创建成功，但消息广播失败");
                WebUtil.writeJson(resp, result);
            }
        } else {
            Result result = Result.error("维护记录创建失败");
            WebUtil.writeJson(resp, result);
        }
    }

    /**
     * 审批维护工单，通知申请人审批结果，并在通过时通知维修人员刷新工单。
     */
    @RequestMapping("/approvalMaintainRecord")
    public void approvalMaintainRecord(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestBody MaintainRecord maintainRecord) {
    System.out.println("maintainRecord=" + maintainRecord);

    maintainRecord.setApprovalTime(new Date());
    Boolean isUpdated = maintainRecordService.updateById(maintainRecord);
    UserNotification userNotification = new UserNotification();
    System.out.println("这里开始调用acl的方法");
    String uname=aclFeignClient.getUsernameById(maintainRecord.getOperatorId());
    System.out.println("acl的方法调用完了");
    userNotification.setUsername(uname);
    userNotification.setNotification("您的报修单"+maintainRecord.getId()+"已被审批，审批结果为："+maintainRecord.getStatus());
    userNotification.setReceiverId(maintainRecord.getOperatorId());
    userNotification.setIsRead(0);
    userNotification.setCreateTime(new Date());
    if (isUpdated) {
        // 1. 通知单个用户（审批结果）
        rabbitTemplate.convertAndSend(
                "approval.direct",   // 交换机
                "user.notify",       // routingKey
                userNotification       // 通知用户
        );
        System.out.println("这里开始准备发送消息给维修员");
       if(maintainRecord.getStatus().equals("已通过")){
           System.out.println("这里开始发送消息给维修员");
           // 2. 通知所有维修员刷新
           rabbitTemplate.convertAndSend(
                   "approval.direct",
                   "repairman.refresh",
                   maintainRecord
           );
       }
        Result result = Result.ok("审批成功！");
        WebUtil.writeJson(resp, result);
    } else {
        Result result = Result.error("审批失败，可能已被他人审批，请刷新后重试！");
        WebUtil.writeJson(resp, result);
        }
    }


    /**
     * 按发起人 ID 分页查询当前用户提交的维护工单。
     */
    @RequestMapping("/getMyMaintainOrder")
    public void getMyMaintainOrder(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                   @RequestParam(value = "pageSize", defaultValue = "3") int pageSize,@RequestParam(value = "userId") String userId) {

        // 创建分页对象
        Page<MaintainRecord> page = new Page<>(pageNum, pageSize);
        QueryWrapper<MaintainRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("operator_id", userId);
        maintainRecordMapper.selectPage(page, queryWrapper);
        List<MaintainRecord> records = page.getRecords();
        Map data = new HashMap();
        data.put("records", records);
        data.put("total", page.getTotal());
        Result result =Result.ok(data);
        WebUtil.writeJson(resp, result);

    }

    /**
     * 按维修人员 ID 分页查询其已领取的维修工单。
     */
    @RequestMapping("/getMyRepairOrder")
    public void getMyRepairOrder(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                   @RequestParam(value = "pageSize", defaultValue = "3") int pageSize,@RequestParam(value = "userId") String userId) {

        // 创建分页对象
        Page<MaintainRecord> page = new Page<>(pageNum, pageSize);
        QueryWrapper<MaintainRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("miantain_id", userId);
        maintainRecordMapper.selectPage(page, queryWrapper);
        List<MaintainRecord> records = page.getRecords();
        Map data = new HashMap();
        data.put("records", records);
        data.put("total", page.getTotal());
        Result result =Result.ok(data);
        WebUtil.writeJson(resp, result);
    }

    /**
     * 将维修工单标记为完成，记录结束时间并把设备状态恢复为“正常”。
     */
    @RequestMapping("/updateMyRepairOrder")
    public void updateMyRepairOrder(HttpServletRequest req, HttpServletResponse resp,
                                   @RequestBody MaintainRecord maintainRecord) {
        System.out.println("maintainRecord=" + maintainRecord);
        maintainRecord.setEndTime(new Date());
        maintainRecord.setStatus("正常");
        Boolean isUpdated = maintainRecordService.updateById(maintainRecord);
        if (isUpdated) {
            Result result = Result.ok("更新成功！");
            WebUtil.writeJson(resp, result);
        } else {
            Result result = Result.error("更新失败！");
            WebUtil.writeJson(resp, result);
        }
    }
//    List<Integer> maintainRecordIds =null;



}

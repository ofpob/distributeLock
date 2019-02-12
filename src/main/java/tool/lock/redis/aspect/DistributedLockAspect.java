package tool.lock.redis.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPool;
import tool.lock.redis.annotation.DistributedLock;
import tool.lock.redis.exception.FrequentOperationException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author: XieZhiDong
 */
@Aspect
@Slf4j
public class DistributedLockAspect {

    private final String[] types = {"java.lang.Integer", "java.lang.Double",
            "java.lang.Float", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Boolean", "java.lang.Char",
            "java.lang.String", "int", "double", "long", "short", "byte",
            "boolean", "char", "float"};

    private ShardedJedisPool shardedJedisPool;

    private String system;

    public void setShardedJedisPool(ShardedJedisPool shardedJedisPool) {
        this.shardedJedisPool = shardedJedisPool;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    @Pointcut("@annotation(tool.lock.redis.annotation.DistributedLock)")
    public void lockPointCut() {

    }

    @Around("lockPointCut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

        String[] keyFields = null;
        if (distributedLock != null) {
            keyFields = distributedLock.keyFields();

        }

        if (keyFields == null) {
            return joinPoint.proceed();
        }

        int expireTime = distributedLock.expireTime();
        String keyPrefix = distributedLock.keyPrefix();
        String operationName = distributedLock.operationName();
        String requestId = UUID.randomUUID().toString();
        String lockKey = parseKey(keyPrefix, keyFields, joinPoint);
        if (tryGetDistributedLock(lockKey, requestId, expireTime)) {
            try {
                return joinPoint.proceed();
            } finally {
                releaseDistributedLock(lockKey, requestId);
            }
        }

        String errMsg = operationName + "操作频繁，请稍后重试";
        throw new FrequentOperationException(errMsg);
    }

    private String parseKey(String keyPrefix, String[] keyFields, ProceedingJoinPoint joinPoint) {
        Map<String, Object> keyValue = new HashMap<>(16);
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                keyValue.put(parameterNames[i], "null");
            } else if (isOriginType(args[i].getClass())) {
                keyValue.put(parameterNames[i], args[i]);
            } else {
                Field[] declaredFields = args[i].getClass().getDeclaredFields();
                for (Field field : declaredFields) {
                    try {
                        //打开私有访问
                        field.setAccessible(true);
                        //获取属性
                        String name = field.getName();
                        //获取属性值
                        Object value = field.get(args[i]);
                        keyValue.put(parameterNames[i] + "." + name, String.valueOf(value));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        String key = Arrays.stream(keyFields).map(k -> {
            if (keyValue.get(k) == null) {
                throw new RuntimeException("parameter " + k + " not found");
            }
            String nullVal = "null";
            if (nullVal.equals(keyValue.get(k))) {
                throw new RuntimeException("parameter " + k + " could not be null");
            }
            return String.valueOf(keyValue.get(k));
        }).collect(Collectors.joining("-"));

        if (null == keyPrefix || "".equals(keyPrefix)) {
            Method targetMethod = signature.getMethod();
            String s = targetMethod.getDeclaringClass().getName() + "." + targetMethod.getName();
            keyPrefix = md5(s);
        }
        return keyPrefix + ":" + key;
    }


    /**
     * 尝试获取分布式锁
     *
     * @param lockKey    锁
     * @param requestId  请求标识
     * @param expireTime 超期时间
     * @return 是否获取成功
     */
    private boolean tryGetDistributedLock(final String lockKey, String requestId, int expireTime) {

        String key = system + lockKey;
        ShardedJedis shardedJedis = shardedJedisPool.getResource();
        if (shardedJedis == null) {
            log.warn("lockKey={},requestId={}获取未获取到redis连接，默认获取锁成功", lockKey, requestId);
            return true;
        }
        try {
            String result = shardedJedis.set(key, requestId, "NX", "EX", expireTime);
            log.info("lockKey={},requestId={},expireTime={}获取redis锁result={}", lockKey, requestId, expireTime, result);
            return "OK".equals(result);
        } catch (Exception e) {
            log.error("lockKey={},requestId={}获取redis锁异常，默认返回成功", lockKey, requestId, e);
            return true;
        } finally {
            shardedJedis.close();
        }
    }

    /**
     * 释放分布式锁
     *
     * @param lockKey   锁
     * @param requestId 请求标识
     */
    private void releaseDistributedLock(final String lockKey, String requestId) {

        String key = system + lockKey;
        ShardedJedis shardedJedis = shardedJedisPool.getResource();
        if (shardedJedis == null) {
            log.warn("lockKey={},requestId={}获取未获取到redis连接，默认解锁成功", lockKey, requestId);
            return;
        }
        //如果解锁标识和key的拥有者相同，则可允许解锁
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        try {
            Object result = shardedJedis.getShard(key).eval(script, Collections.singletonList(key), Collections.singletonList(requestId));
            log.info("lockKey={},requestId={}解锁result={}", lockKey, requestId, result);
        } catch (Exception e) {
            log.error("lockKey={},requestId={}解锁异常，默认返回成功", lockKey, requestId, e);
        } finally {
            shardedJedis.close();
        }
    }

    private boolean isOriginType(Class clazz) {
        return Arrays.stream(types).parallel()
                .anyMatch(t -> t.equals(clazz.getName()));
    }

    /**
     * md5加密的方法
     */
    private String md5(String plainText) {
        //定义一个字节数组
        byte[] secretBytes;
        try {
            // 生成一个MD5加密计算摘要
            MessageDigest md = MessageDigest.getInstance("MD5");
            //对字符串进行加密
            md.update(plainText.getBytes());
            //获得加密后的数据
            secretBytes = md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("没有md5这个算法！");
        }
        //将加密后的数据转换为16进制数字
        String md5code = new BigInteger(1, secretBytes).toString(16);
        StringBuilder md5Builder = new StringBuilder(md5code);
        // 如果生成数字未满32位，需要前面补0
        int md5Length = 32;
        for (int i = 0; i < md5Length - md5code.length(); i++) {
            md5Builder.insert(0,"0");
        }
        return md5Builder.toString();
    }

}

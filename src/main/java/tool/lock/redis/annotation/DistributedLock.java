package tool.lock.redis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /**
     * 封装key所需的参数名数组、如果为原始变量则使用参数名
     * 例如test(String name)，则使用keyFields = {"name"}
     * 如果使用对象的某个属性
     * 例如 使用 class request {
     *     private String name;
     *     private Integer id;
     * } 中的 name 和id
     * 参数为 test(Request r)， 则应使用keyFields = {"request.orderCode","request.orderType"}
     *
     * 如果为test(Request r,String name)则应使用keyFields = {"request.orderCode","request.orderType","name"}
     *
     * @return 主键参数名list
     */
    String[] keyFields();

    /**
     * key的前缀，表示业务的唯一标示确保相同参数的不同业务方法互不影响
     * 例如 saveA(String key) 和 saveB(String key) 都使用key 作为主键，当key都为同一个值的时候（"a"），
     * 为了保证互补影响，则saveA 和 saveB 应有不同的前缀表示
     * 如果使用默认值或null 将使用方法全名的md5值作为前缀
     * @return 主键前缀
     */
    String keyPrefix() default "";

    /**
     * 加锁操作的操作名，用于提示未获取到锁时的友好提示
     * @return 操作名
     */
    String operationName();

    /**
     * 默认超时时间 6分钟
     * @return 超时时间 单位为s
     */
    int expireTime() default 5 * 60;
}

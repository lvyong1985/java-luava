package io.github.lvyong1985.luava.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author LvYong
 * @create 2018/6/29
 **/
public class RateLimiter {

  /**
   * 速率单位转换成毫秒
   */
  private final long rateToMsConversion;

  /**
   * 消耗令牌数
   */
  private final AtomicInteger consumedTokens = new AtomicInteger();

  /**
   * 最后填充令牌的时间
   */
  private final AtomicLong lastRefillTime = new AtomicLong(0);

  public RateLimiter(TimeUnit averageRateUnit) {
    switch (averageRateUnit) {
      case SECONDS: // 秒级
        rateToMsConversion = 1000;
        break;
      case MINUTES: // 分钟级
        rateToMsConversion = 60 * 1000;
        break;
      default:
        throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
    }
  }

  /**
   * 获取令牌( Token )
   *
   * @param burstSize 令牌桶上限
   * @param averageRate 令牌再装平均速率
   * @return 是否获取成功
   */
  public boolean acquire(int burstSize, long averageRate) {
    return acquire(burstSize, averageRate, System.currentTimeMillis());
  }

  public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
    if (burstSize <= 0
        || averageRate <= 0) { // Instead of throwing exception, we just let all the traffic go
      return true;
    }

    // 填充 令牌
    refillToken(burstSize, averageRate, currentTimeMillis);
    // 消费 令牌
    return consumeToken(burstSize);
  }

  private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
    // 获得 最后填充令牌的时间
    long refillTime = lastRefillTime.get();
    // 获得 过去多少毫秒
    long timeDelta = currentTimeMillis - refillTime;

    // 计算 可填充最大令牌数量
    long newTokens = timeDelta * averageRate / rateToMsConversion;
    if (newTokens > 0) {
      // 计算 新的填充令牌的时间
      long newRefillTime = refillTime == 0
          ? currentTimeMillis
          : refillTime + newTokens * rateToMsConversion / averageRate;
      // CAS 保证有且仅有一个线程进入填充
      if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
        while (true) { // 死循环，直到成功
          // 计算 填充令牌后的已消耗令牌数量
          int currentLevel = consumedTokens.get();
          int adjustedLevel = Math.min(currentLevel, burstSize); // In case burstSize decreased
          int newLevel = (int) Math.max(0, adjustedLevel - newTokens);
          // CAS 避免和正在消费令牌的线程冲突
          if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
            return;
          }
        }
      }
    }
  }

  private boolean consumeToken(int burstSize) {
    while (true) { // 死循环，直到没有令牌，或者获取令牌成功
      // 没有令牌
      int currentLevel = consumedTokens.get();
      if (currentLevel >= burstSize) {
        return false;
      }
      // CAS 避免和正在消费令牌或者填充令牌的线程冲突
      if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
        return true;
      }
    }
  }

  public void reset() {
    consumedTokens.set(0);
    lastRefillTime.set(0);
  }

}
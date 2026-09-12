package org.bluesky.training.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记 /api/v2 控制器；v2 错误信封与横切行为只作用于带此标注的控制器。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface V2Api {
}

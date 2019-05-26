package com.stolsvik.mats.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Enables Mats-specific bean-scanning for methods annotated with {@link MatsMapping} and {@link MatsStaged},
 * conceptually inspired by the {@literal @EnableWebMvc} annotation. Methods having the specified annotations will get
 * Mats endpoints set up for them.
 * <p>
 * This annotation imports the {@link MatsSpringConfiguration} class, which is a Spring
 * {@link Configuration @Configuration}. Read more JavaDoc there!
 *
 * @author Endre Stølsvik - 2016-05-21 - http://endre.stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(MatsSpringConfiguration.class)
@Documented
public @interface EnableMats {

}

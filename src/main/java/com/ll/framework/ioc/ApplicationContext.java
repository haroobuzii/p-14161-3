package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

// 스프링의 IoC 컨테이너를 흉내 낸 클래스 (v3: 컴포넌트 스캔 + @Configuration·@Bean)
// 빈을 만드는 방법은 두 가지다
//   1. 컴포넌트 스캔: @Component(@Service·@Repository·@Configuration) 가 붙은 클래스 → 생성자를 호출해서 만든다
//   2. @Bean 메서드: @Configuration 클래스 안의 @Bean 메서드 → 그 메서드를 호출한 반환값이 빈이 된다
//      (ObjectMapper 처럼 내가 만든 클래스가 아니라 @Component 를 붙일 수 없는 외부 라이브러리 객체를 빈으로 등록할 때 쓴다)
public class ApplicationContext {
    private final String basePackage;

    // 빈 이름 → 빈 생성 정보 (어떤 타입이고, 어떻게 만드는지)
    private final Map<String, BeanDefinition> beanDefinitions = new LinkedHashMap<>();

    // 빈 이름 → 만들어 둔 빈 객체 (싱글톤 보관함)
    private final Map<String, Object> beans = new HashMap<>();

    // 지금 만드는 중인 빈 이름들 (순환 의존 감지용)
    private final Set<String> beansInCreation = new LinkedHashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    // 1. 패키지를 스캔해 빈 생성 정보를 모으고  2. 모든 빈을 미리 만들어 둔다
    public void init() {
        scanComponents();

        for (String beanName : List.copyOf(beanDefinitions.keySet())) {
            genBean(beanName);
        }
    }

    // ---------- 1. 스캔: 빈 생성 정보 모으기 ----------

    // @Service·@Repository·@Configuration 에는 @Component 가 붙어 있어서(메타 어노테이션) 함께 찾아진다
    // Reflections 가 이 관계를 알려면 어노테이션 패키지도 같이 스캔해야 한다 → 둘 다 스캔하고, 결과는 basePackage 것만 쓴다
    private void scanComponents() {
        Reflections reflections = new Reflections(basePackage, Component.class.getPackageName());

        for (Class<?> cls : reflections.getTypesAnnotatedWith(Component.class)) {
            if (cls.isAnnotation() || cls.isInterface()) continue; // @Service 같은 어노테이션 자체는 빈이 아니다
            if (!cls.getName().startsWith(basePackage + ".")) continue; // 요청한 패키지 밖의 클래스는 제외

            String beanName = Ut.str.lcfirst(cls.getSimpleName()); // 예) TestPostService → testPostService
            register(beanName, new ComponentBean(cls));

            // @Configuration 클래스라면, 그 안의 @Bean 메서드도 하나씩 빈으로 등록한다
            if (cls.isAnnotationPresent(Configuration.class)) {
                for (Method method : cls.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(Bean.class)) continue;

                    // @Bean 메서드 이름이 빈 이름이 된다  예) testBaseObjectMapper()
                    register(method.getName(), new BeanMethodBean(beanName, method));
                }
            }
        }
    }

    private void register(String beanName, BeanDefinition definition) {
        BeanDefinition old = beanDefinitions.putIfAbsent(beanName, definition);

        if (old != null) {
            throw new IllegalStateException("빈 이름이 겹칩니다: " + beanName + " (" + old.type().getName() + ", " + definition.type().getName() + ")");
        }
    }

    // ---------- 2. 꺼내기: 없으면 만들고, 있으면 그대로 (싱글톤) ----------

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);

        if (bean != null) return (T) bean;

        BeanDefinition definition = beanDefinitions.get(beanName);

        if (definition == null) throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);

        bean = createBean(beanName, definition);
        beans.put(beanName, bean);

        return (T) bean;
    }

    private Object createBean(String beanName, BeanDefinition definition) {
        // A 가 B 를, B 가 A 를 필요로 하면 끝없이 서로를 만들려고 한다 → 바로 알려준다
        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 있습니다: " + beansInCreation + " → " + beanName);
        }

        try {
            return switch (definition) {
                // 생성자 파라미터를 다른 빈으로 채워서 생성자 호출
                case ComponentBean(Class<?> cls) -> {
                    Constructor<?> constructor = pickConstructor(cls);
                    constructor.setAccessible(true);

                    yield constructor.newInstance(resolveArgs(constructor));
                }
                // @Configuration 빈을 먼저 꺼내고, @Bean 메서드 파라미터를 다른 빈으로 채워서 메서드 호출
                case BeanMethodBean(String configBeanName, Method method) -> {
                    Object configBean = genBean(configBeanName);
                    method.setAccessible(true);

                    yield method.invoke(configBean, resolveArgs(method));
                }
            };
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(beanName + " 빈을 만들지 못했습니다.", e);
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    // ---------- 3. 의존성 주입: 파라미터마다 알맞은 빈 찾기 ----------

    // 생성자·메서드의 파라미터 하나하나에 넣을 빈을 찾아 배열로 만든다
    private Object[] resolveArgs(Executable executable) {
        return Arrays.stream(executable.getParameters())
                .map(this::resolveArg)
                .toArray();
    }

    // 파라미터 "타입"으로 빈을 찾는다
    // (컴파일하면 파라미터 이름은 보통 arg0 처럼 사라지므로 타입이 가장 확실한 단서다)
    // 같은 타입의 빈이 여러 개면 파라미터 이름이 남아 있을 때 이름이 같은 빈을 고른다
    private Object resolveArg(Parameter parameter) {
        Class<?> type = parameter.getType();

        List<String> candidates = beanDefinitions.entrySet()
                .stream()
                .filter(entry -> type.isAssignableFrom(entry.getValue().type()))
                .map(Map.Entry::getKey)
                .toList();

        if (candidates.size() == 1) return genBean(candidates.getFirst());

        if (candidates.size() > 1 && parameter.isNamePresent() && candidates.contains(parameter.getName())) {
            return genBean(parameter.getName());
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException(type.getName() + " 타입의 빈이 없습니다.");
        }

        throw new IllegalStateException(type.getName() + " 타입의 빈이 여러 개라 고를 수 없습니다: " + candidates);
    }

    // 생성자가 여러 개면 파라미터가 가장 많은 것을 쓴다 (@RequiredArgsConstructor 가 만든 생성자)
    private static Constructor<?> pickConstructor(Class<?> cls) {
        return Arrays.stream(cls.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
    }

    // ---------- 빈 생성 정보 ----------

    // 빈 하나를 "어떻게" 만드는지. type 은 만들어질 빈의 타입 (파라미터 타입으로 빈을 찾을 때 쓴다)
    private sealed interface BeanDefinition permits ComponentBean, BeanMethodBean {
        Class<?> type();
    }

    // 컴포넌트 스캔으로 찾은 클래스 → 생성자로 만든다
    private record ComponentBean(Class<?> cls) implements BeanDefinition {
        public Class<?> type() {
            return cls;
        }
    }

    // @Configuration 안의 @Bean 메서드 → configBeanName 빈의 method 를 호출해서 만든다
    private record BeanMethodBean(String configBeanName, Method method) implements BeanDefinition {
        public Class<?> type() {
            return method.getReturnType();
        }
    }
}

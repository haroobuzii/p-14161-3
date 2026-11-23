package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.util.*;

// 스프링의 IoC 컨테이너를 흉내 낸 클래스 (v2: 컴포넌트 스캔)
// v1 처럼 "어떤 클래스를 new 할지" 하드코딩하지 않고, 패키지를 훑어서(@Component 가 붙은 클래스) 자동으로 빈을 만든다
public class ApplicationContext {
    private final String basePackage;

    // 빈 이름 → 빈 클래스 (스캔해서 모은 "빈 생성 정보")
    private final Map<String, Class<?>> beanDefinitions = new HashMap<>();

    // 빈 이름 → 만들어 둔 빈 객체 (싱글톤 보관함)
    private final Map<String, Object> beans = new HashMap<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    // 지금 만드는 중인 빈 이름들 (순환 의존 감지용)
    private final Set<String> beansInCreation = new LinkedHashSet<>();

    // 1. 패키지를 스캔해 빈 생성 정보를 모으고  2. 모든 빈을 미리 만들어 둔다 (스프링도 시작할 때 싱글톤 빈을 미리 만든다)
    public void init() {
        scanComponents();

        for (String beanName : beanDefinitions.keySet()) {
            genBean(beanName);
        }
    }

    // basePackage 안에서 @Component 가 붙은 클래스를 모두 찾는다
    // @Service·@Repository·@Configuration 에도 @Component 가 붙어 있어서(메타 어노테이션) 함께 찾아진다
    // 단, Reflections 가 "@Service 에 @Component 가 붙어 있다"는 걸 알려면 어노테이션 패키지도 같이 스캔해야 한다
    // → basePackage 가 com.myapp 처럼 프레임워크 패키지를 포함하지 않아도 동작하도록 둘 다 스캔하고, 결과는 basePackage 것만 쓴다
    private void scanComponents() {
        Reflections reflections = new Reflections(basePackage, Component.class.getPackageName());

        for (Class<?> cls : reflections.getTypesAnnotatedWith(Component.class)) {
            if (cls.isAnnotation() || cls.isInterface()) continue; // @Service 같은 어노테이션 자체는 빈이 아니다
            if (!cls.getName().startsWith(basePackage + ".")) continue; // 요청한 패키지 밖의 클래스는 제외

            beanDefinitions.put(toBeanName(cls), cls);
        }
    }

    // 클래스 이름의 첫 글자를 소문자로 → 빈 이름   예) TestPostService → testPostService
    private static String toBeanName(Class<?> cls) {
        return Ut.str.lcfirst(cls.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        // 이미 만든 빈이 있으면 그대로 돌려준다 (싱글톤)
        Object bean = beans.get(beanName);

        if (bean != null) return (T) bean;

        Class<?> cls = beanDefinitions.get(beanName);

        if (cls == null) throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);

        bean = createBean(cls);
        beans.put(beanName, bean);

        return (T) bean;
    }

    // 생성자 주입: 생성자 파라미터 타입을 보고, 필요한 빈을 먼저 genBean 으로 받아서 넣는다
    // 예) TestPostService(TestPostRepository testPostRepository)
    //     → 파라미터 타입 TestPostRepository → 빈 이름 testPostRepository → genBean 으로 받아 생성자에 전달
    private Object createBean(Class<?> cls) {
        String beanName = toBeanName(cls);

        // A 가 B 를, B 가 A 를 필요로 하면 끝없이 서로를 만들려고 한다 → 바로 알려준다
        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 있습니다: " + beansInCreation + " → " + beanName);
        }

        try {
            Constructor<?> constructor = pickConstructor(cls);
            constructor.setAccessible(true);

            Object[] args = Arrays.stream(constructor.getParameterTypes())
                    .map(paramType -> genBean(toBeanName(paramType)))
                    .toArray();

            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(cls.getName() + " 빈을 만들지 못했습니다.", e);
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    // 생성자가 여러 개면 파라미터가 가장 많은 것을 쓴다 (@RequiredArgsConstructor 가 만든 생성자)
    private static Constructor<?> pickConstructor(Class<?> cls) {
        return Arrays.stream(cls.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
    }
}

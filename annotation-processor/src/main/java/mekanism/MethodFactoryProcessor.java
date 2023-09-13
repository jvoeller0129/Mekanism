package mekanism;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
import mekanism.visitors.AnnotationHelper;

import javax.lang.model.SourceVersion;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;

/**
 * Gathering (Gradle) annotation processor which generates a ComputerMethodRegistry for the Factories generated
 * by the {@link ComputerMethodProcessor} processor.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes(MekAnnotationProcessors.COMPUTER_METHOD_FACTORY_ANNOTATION_CLASSNAME)
@SupportedOptions(MekAnnotationProcessors.MODULE_OPTION)
public class MethodFactoryProcessor extends AbstractProcessor {
    private String mekModule;
    private final ClassName factoryRegistry = ClassName.get(MekAnnotationProcessors.COMPUTER_INTEGRATION_PACKAGE, "FactoryRegistry");
    private final ClassName methodRegistryInterface = ClassName.get(MekAnnotationProcessors.COMPUTER_INTEGRATION_PACKAGE, "IComputerMethodRegistry");
    private final MethodSpec.Builder registryInit = MethodSpec.methodBuilder("register")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class);

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        mekModule = processingEnv.getOptions().getOrDefault(MekAnnotationProcessors.MODULE_OPTION, "value_not_supplied");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotatedTypes, RoundEnvironment roundEnvironment) {
        TypeMirror methodFactoryType = processingEnv.getElementUtils().getTypeElement(MekAnnotationProcessors.COMPUTER_METHOD_FACTORY_ANNOTATION_CLASSNAME).asType();
        TypeSpec.Builder registryType = TypeSpec.classBuilder("ComputerMethodRegistry_" + mekModule)
              .addModifiers(Modifier.PUBLIC)
              .addSuperinterface(methodRegistryInterface);

        //this should only ever be 1 annotation
        for (Element element : roundEnvironment.getElementsAnnotatedWithAny(annotatedTypes.toArray(new TypeElement[0]))) {
            if (element instanceof TypeElement factoryTypeEl) {
                //get the annotation mirror for @MethodFactory
                AnnotationMirror annotationMirror = null;
                for (AnnotationMirror am : factoryTypeEl.getAnnotationMirrors()) {
                    if (typeUtils().isSameType(am.getAnnotationType(), methodFactoryType)) {
                        annotationMirror = am;
                        break;
                    }
                }
                if (annotationMirror == null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Couldn't find annotation mirror", factoryTypeEl);
                    continue;
                }
                registryType.addOriginatingElement(factoryTypeEl);
                AnnotationHelper helper = new AnnotationHelper(processingEnv.getElementUtils(), annotationMirror);
                addHandlerToRegistry((TypeElement) typeUtils().asElement(helper.getClassValue("target")), ClassName.get(factoryTypeEl));
            }
        }

        if (!registryType.originatingElements.isEmpty()) {
            registryType.addMethod(registryInit.build());
            TypeSpec registrySpec = registryType.build();
            String packageName = "mekanism.generated." + mekModule;
            try {
                JavaFile.builder(packageName, registrySpec).build().writeTo(processingEnv.getFiler());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try(Writer serviceWriter = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "","META-INF/services/"+methodRegistryInterface.canonicalName()).openWriter()) {
                serviceWriter.write(packageName+"."+registrySpec.name);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    /**
     * Gather superclasses for handledType and add a register call
     * @param handledType the subject type of the handler
     * @param factoryClassName the factory's class name
     */
    private void addHandlerToRegistry(TypeElement handledType, ClassName factoryClassName) {
        //gather all superclasses (in mekanism package)
        List<ClassName> superClasses = new ArrayList<>();
        TypeMirror superClass = handledType.getSuperclass();
        TypeElement superTypeElement;
        do {
            superTypeElement = (TypeElement) typeUtils().asElement(superClass);
            if (superTypeElement == null) {
                break;
            }
            ClassName clazz = ClassName.get(superTypeElement);
            if (clazz.canonicalName().startsWith("mekanism")) {
                superClasses.add(0, clazz);
            }
        } while ((superClass = superTypeElement.getSuperclass()).getKind() != TypeKind.NONE);

        //add register call to the factory
        String registerName = handledType.getKind() == ElementKind.INTERFACE ? "registerInterface" : "register";
        CodeBlock.Builder registerStatement = CodeBlock.builder()
                .add("$T.$L($T.class, $T::new", factoryRegistry, registerName, typeUtils().erasure(handledType.asType()), factoryClassName);
        //add all super classes, so we don't have to calculate at runtime
        for (ClassName cls : superClasses) {
            registerStatement.add(", $T.class", cls);
        }
        registerStatement.add(")");
        registryInit.addStatement(registerStatement.build());
    }

    private Types typeUtils() {
        return processingEnv.getTypeUtils();
    }
}

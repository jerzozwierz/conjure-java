/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.Iterables;
import com.palantir.conjure.java.ConjureAnnotations;
import com.palantir.conjure.spec.EnumDefinition;
import com.palantir.conjure.spec.EnumValueDefinition;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;

public final class EnumGenerator {

    private static final TypeVariableName TYPE_VARIABLE = TypeVariableName.get("T");
    private static final String VISIT_UNKNOWN_METHOD_NAME = "visitUnknown";
    private static final String VISIT_METHOD_NAME = "visit";

    private EnumGenerator() {}

    public static JavaFile generateEnumType(EnumDefinition typeDef) {
        String typePackage = typeDef.getTypeName().getPackage();

        return JavaFile.builder(typePackage, createSafeEnum(typeDef))
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
    }

    private static TypeSpec createSafeEnum(EnumDefinition typeDef) {
        String typePackage = typeDef.getTypeName().getPackage();
        ClassName thisClass = ClassName.get(typePackage, typeDef.getTypeName().getName());
        ClassName enumClass = ClassName.get(typePackage, typeDef.getTypeName().getName(), "Value");
        ClassName visitorClass = ClassName.get(typePackage, thisClass.simpleName(), "Visitor");

        TypeSpec.Builder wrapper = TypeSpec.classBuilder(typeDef.getTypeName().getName())
                .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(EnumGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addType(createEnum(enumClass, typeDef.getValues(), true))
                .addType(createVisitor(visitorClass, typeDef.getValues(), true))
                .addField(enumClass, "value", Modifier.PRIVATE, Modifier.FINAL)
                .addField(ClassName.get(String.class), "string", Modifier.PRIVATE, Modifier.FINAL)
                .addFields(createConstants(typeDef.getValues(), thisClass, enumClass))
                .addMethod(createConstructor(enumClass))
                .addMethod(MethodSpec.methodBuilder("get")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(enumClass)
                        .addStatement("return this.value")
                        .build())
                .addMethod(createAcceptVisitor(visitorClass, typeDef.getValues()))
                .addMethod(MethodSpec.methodBuilder("toString")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .addAnnotation(JsonValue.class)
                        .returns(ClassName.get(String.class))
                        .addStatement("return this.string")
                        .build())
                .addMethod(createEquals(thisClass))
                .addMethod(createHashCode())
                .addMethod(createValueOf(thisClass, typeDef.getValues()));

        typeDef.getDocs().ifPresent(
                docs -> wrapper.addJavadoc("$L<p>\n", StringUtils.appendIfMissing(docs.get(), "\n")));

        wrapper.addJavadoc(
                "This class is used instead of a native enum to support unknown values.\n"
                        + "Rather than throw an exception, the {@link $1T#valueOf} method defaults to a new "
                        + "instantiation of\n{@link $1T} where {@link $1T#get} will return {@link $2T#UNKNOWN}.\n"
                        + "<p>\n"
                        + "For example, {@code $1T.valueOf(\"corrupted value\").get()} will return "
                        + "{@link $2T#UNKNOWN},\nbut {@link $1T#toString} will return \"corrupted value\".\n"
                        + "<p>\n"
                        + "There is no method to access all instantiations of this class, since they cannot be known "
                        + "at compile time.\n",
                thisClass,
                enumClass
        );

        return wrapper.build();
    }

    private static Iterable<FieldSpec> createConstants(Iterable<EnumValueDefinition> values,
            ClassName thisClass, ClassName enumClass) {
        return Iterables.transform(values,
                v -> {
                    FieldSpec.Builder fieldSpec = FieldSpec.builder(thisClass, v.getValue(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer(CodeBlock.of("new $1T($2T.$3N, $4S)", thisClass, enumClass,
                                    v.getValue(), v.getValue()));
                    v.getDocs().ifPresent(
                            docs -> fieldSpec.addJavadoc("$L", StringUtils.appendIfMissing(docs.get(), "\n")));
                    return fieldSpec.build();
                });
    }

    private static TypeSpec createEnum(ClassName enumClass, Iterable<EnumValueDefinition> values, boolean withUnknown) {
        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(enumClass.simpleName())
                .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(EnumGenerator.class))
                .addModifiers(Modifier.PUBLIC);
        for (EnumValueDefinition value : values) {
            TypeSpec.Builder anonymousClassBuilder = TypeSpec.anonymousClassBuilder("");
            value.getDocs().ifPresent(docs ->
                    anonymousClassBuilder.addJavadoc("$L", StringUtils.appendIfMissing(docs.get(), "\n")));
            enumBuilder.addEnumConstant(value.getValue(), anonymousClassBuilder.build());
        }
        if (withUnknown) {
            enumBuilder.addEnumConstant("UNKNOWN");
        } else {
            enumBuilder.addMethod(MethodSpec.methodBuilder("fromString")
                    .addJavadoc("$L", "Preferred, case-insensitive constructor for string-to-enum conversion.\n")
                    .addAnnotation(JsonCreator.class)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(ClassName.get(String.class), "value")
                    .addStatement("return $T.valueOf(value.toUpperCase($T.ROOT))", enumClass, Locale.class)
                    .returns(enumClass)
                    .build());
        }
        return enumBuilder.build();
    }

    private static TypeSpec createVisitor(
            ClassName visitorClass,
            List<EnumValueDefinition> values,
            boolean withUnknown) {
        TypeSpec.Builder builder = TypeSpec.interfaceBuilder(visitorClass)
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TYPE_VARIABLE)
                .addMethods(createVisitorMethodSpecs(values));

        if (withUnknown) {
            builder = builder.addMethod(MethodSpec.methodBuilder(VISIT_UNKNOWN_METHOD_NAME)
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addParameter(String.class, "unknownValue")
                    .returns(TYPE_VARIABLE)
                    .build());
        }
        return builder.build();
    }

    private static List<MethodSpec> createVisitorMethodSpecs(List<EnumValueDefinition> enumValueDefinitions) {
        return enumValueDefinitions.stream().map(def ->
            MethodSpec.methodBuilder(VISIT_METHOD_NAME + StringUtils.capitalize(def.getValue().toLowerCase(Locale.ROOT)))
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(TYPE_VARIABLE)
                    .build())
                .collect(Collectors.toList());
    }

    private static MethodSpec createAcceptVisitor(ClassName visitorClass, List<EnumValueDefinition> values) {
        ParameterizedTypeName parameterizedVisitorClass = ParameterizedTypeName.get(visitorClass, TYPE_VARIABLE);
        ParameterSpec param = ParameterSpec.builder(parameterizedVisitorClass, "visitor").build();

        CodeBlock.Builder parser = CodeBlock.builder()
                .beginControlFlow("switch (this.get())");
        values.forEach(value -> {
            String methodName = VISIT_METHOD_NAME + StringUtils.capitalize(value.getValue().toLowerCase(Locale.ROOT));
            parser.add("case $L:\n", value.getValue())
                    .indent()
                    .addStatement("return visitor.$L()", methodName)
                    .unindent();
        });
        parser.add("default:\n")
                .indent()
                .addStatement("return visitor.visitUnknown(this.toString())")
                .unindent()
                .endControlFlow();

        return MethodSpec.methodBuilder("accept")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TYPE_VARIABLE)
                .returns(TYPE_VARIABLE)
                .addParameter(param)
                .addCode(parser.build())
                .build();
    }

    private static MethodSpec createConstructor(ClassName enumClass) {
        // Note: We generate a two arg constructor that handles both known
        // and unknown variants instead of using separate contructors to avoid
        // jackson's behavior of preferring single string contructors for key
        // deserializers over static factories.
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(enumClass, "value")
                .addParameter(ClassName.get(String.class), "string")
                .addStatement("this.value = value")
                .addStatement("this.string = string")
                .build();
    }

    private static MethodSpec createValueOf(ClassName thisClass, Iterable<EnumValueDefinition> values) {
        ParameterSpec param = ParameterSpec.builder(ClassName.get(String.class), "value").build();

        CodeBlock.Builder parser = CodeBlock.builder()
                .beginControlFlow("switch (upperCasedValue)");
        for (EnumValueDefinition value : values) {
            parser.add("case $S:\n", value.getValue())
                    .indent()
                    .addStatement("return $L", value.getValue())
                    .unindent();
        }
        parser.add("default:\n")
                .indent()
                .addStatement("return new $T(Value.UNKNOWN, upperCasedValue)", thisClass)
                .unindent()
                .endControlFlow();

        return MethodSpec.methodBuilder("valueOf")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(thisClass)
                .addAnnotation(JsonCreator.class)
                .addParameter(param)
                .addStatement("$L", Expressions.requireNonNull(param.name, param.name + " cannot be null"))
                // uppercase param for backwards compatibility
                .addStatement("String upperCasedValue = $N.toUpperCase($T.ROOT)", param, Locale.class)
                .addCode(parser.build())
                .build();
    }

    private static MethodSpec createEquals(TypeName thisClass) {
        ParameterSpec other = ParameterSpec.builder(TypeName.OBJECT, "other").build();
        return MethodSpec.methodBuilder("equals")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(other)
                .returns(TypeName.BOOLEAN)
                .addStatement("return (this == $1N) || ($1N instanceof $2T && this.string.equals((($2T) $1N).string))",
                        other, thisClass)
                .build();
    }

    private static MethodSpec createHashCode() {
        return MethodSpec.methodBuilder("hashCode")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.INT)
                .addStatement("return this.string.hashCode()")
                .build();
    }

}

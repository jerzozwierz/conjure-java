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

import com.palantir.conjure.java.FeatureFlags;
import com.palantir.conjure.java.util.BinaryReturnTypeResolver;
import com.palantir.conjure.spec.ExternalReference;
import com.palantir.conjure.spec.ListType;
import com.palantir.conjure.spec.MapType;
import com.palantir.conjure.spec.OptionalType;
import com.palantir.conjure.spec.PrimitiveType;
import com.palantir.conjure.spec.SetType;
import com.palantir.conjure.spec.TypeDefinition;
import com.palantir.conjure.visitor.TypeDefinitionVisitor;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

public final class JerseyReturnTypeClassNameVisitor implements ClassNameVisitor {

    private final boolean binaryAsResponse;
    private final DefaultClassNameVisitor delegate;
    private final Map<com.palantir.conjure.spec.TypeName, TypeDefinition> types;

    public JerseyReturnTypeClassNameVisitor(List<TypeDefinition> types, Set<FeatureFlags> featureFlags) {
        this.types = types.stream().collect(
                Collectors.toMap(t -> t.accept(TypeDefinitionVisitor.TYPE_NAME), Function.identity()));
        this.delegate = new DefaultClassNameVisitor(types);
        this.binaryAsResponse = featureFlags.contains(FeatureFlags.JerseyBinaryAsResponse);
    }

    @Override
    public TypeName visitList(ListType type) {
        return delegate.visitList(type);
    }

    @Override
    public TypeName visitMap(MapType type) {
        return delegate.visitMap(type);
    }

    @Override
    public TypeName visitOptional(OptionalType type) {
        return delegate.visitOptional(type);
    }

    @Override
    public TypeName visitPrimitive(PrimitiveType type) {
        if (type.get() == PrimitiveType.Value.BINARY) {
            if (binaryAsResponse) {
                return ClassName.get(Response.class);
            } else {
                return ClassName.get(StreamingOutput.class);
            }
        }
        return delegate.visitPrimitive(type);
    }

    @Override
    public TypeName visitReference(com.palantir.conjure.spec.TypeName type) {
        return BinaryReturnTypeResolver.resolveReturnReferenceType(types, type,
                binaryAsResponse ? ClassName.get(Response.class) : ClassName.get(StreamingOutput.class));
    }

    @Override
    public TypeName visitExternal(ExternalReference type) {
        return delegate.visitExternal(type);
    }

    @Override
    public TypeName visitSet(SetType type) {
        return delegate.visitSet(type);
    }

}

/*
 * Copyright (c) 2014, Danilo Reinert (daniloreinert@growbit.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of both licenses is available under the src/resources/ directory of
 * this project (under the names LGPL-3.0.txt and ASL-2.0.txt respectively).
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.github.reinert.jjschema.v1;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.reinert.jjschema.Attributes;
import com.github.reinert.jjschema.ManagedReference;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

/**
 * @author Danilo Reinert
 */

public class CustomSchemaWrapper extends SchemaWrapper implements Iterable<PropertyWrapper> {

    public static final String TAG_REQUIRED = "required";
    public static final String TAG_PROPERTIES = "properties";
    
    private final List<PropertyWrapper> propertyWrappers;
    private boolean required;
    private final Set<ManagedReference> managedReferences;
    private String relativeId = "#";

    public CustomSchemaWrapper(Class<?> type) {
        this(type, false);
    }

    public CustomSchemaWrapper(Class<?> type, boolean ignoreProperties) {
        this(type, new HashSet<ManagedReference>(), null, ignoreProperties);
    }

    public CustomSchemaWrapper(Class<?> type, Set<ManagedReference> managedReferences, boolean ignoreProperties) {
        this(type, managedReferences, null, ignoreProperties);
    }

    public CustomSchemaWrapper(Class<?> type, Set<ManagedReference> managedReferences, String relativeId, boolean ignoreProperties) {
        super(type);
        setType("object");
        processNullable();
        processAttributes(getNode(), type);
        this.managedReferences = managedReferences;

        if (relativeId != null) {
            addTokenToRelativeId(relativeId);
        }

        if (ignoreProperties) {
            this.propertyWrappers = null;
            return;
        }

        this.propertyWrappers = Lists.newArrayListWithExpectedSize(type.getDeclaredFields().length);
        processProperties();
    }

    public String getRelativeId() {
        return relativeId;
    }

    protected void addTokenToRelativeId(String token) {
        if (token.startsWith("#"))
            relativeId = token;
        else
            relativeId = relativeId + "/" + token;
    }

    public void addProperty(PropertyWrapper propertyWrapper) {
        this.propertyWrappers.add(propertyWrapper);

        if (!getNode().has(TAG_PROPERTIES))
            getNode().putObject(TAG_PROPERTIES);

        ((ObjectNode) getNode().get(TAG_PROPERTIES)).put(propertyWrapper.getName(), propertyWrapper.asJson());

        if (propertyWrapper.isRequired())
            addRequired(propertyWrapper.getName());
    }

//    public boolean removeProperty(PropertyWrapper propertyWrapper) {
//        return propertyWrappers.remove(propertyWrapper);
//    }
//
//    public void clearProperties() {
//        propertyWrappers.clear();
//    }

    public boolean isRequired() {
        return required;
    }

    public void addRequired(String name) {
        if (!getNode().has(TAG_REQUIRED))
            getNode().putArray(TAG_REQUIRED);
        ArrayNode requiredNode = (ArrayNode) getNode().get(TAG_REQUIRED);
        requiredNode.add(name);
    }

    public boolean pullReference(ManagedReference managedReference) {
        if (managedReferences.contains(managedReference))
            return false;
        managedReferences.add(managedReference);
        return true;
    }

    public boolean pushReference(ManagedReference managedReference) {
        return managedReferences.remove(managedReference);
    }

    @Override
    public boolean isCustomWrapper() {
        return true;
    }

    /**
     * Returns an iterator over a set of elements of PropertyWrapper.
     *
     * @return an Iterator.
     */
    @Override
    public Iterator<PropertyWrapper> iterator() {
        return propertyWrappers != null ? propertyWrappers.iterator() : Collections.<PropertyWrapper>emptyIterator();
    }

    protected void processProperties() {
        HashMap<Method, Field> properties = findProperties();
        for (Entry<Method, Field> prop : properties.entrySet()) {
            PropertyWrapper propertyWrapper = new PropertyWrapper(this, managedReferences, 
                    prop.getKey(), prop.getValue());
            if (!propertyWrapper.isEmptyWrapper())
                addProperty(propertyWrapper);
        }
    }

    private HashMap<Method, Field> findProperties() {
        Field[] fields = new Field[0];
        Class<?> javaType = getJavaType();
        while(javaType.getSuperclass() != null) {
            fields = ObjectArrays.concat(fields, javaType.getDeclaredFields(), Field.class);
            javaType = javaType.getSuperclass();
        }

        Method[] methods = getJavaType().getMethods();
        // Ordering the properties
        Arrays.sort(methods, new Comparator<Method>() {
            public int compare(Method m1, Method m2) {
                return m1.getName().compareTo(m2.getName());
            }
        });

        LinkedHashMap<Method, Field> props = new LinkedHashMap<Method, Field>();
        // get valid properties (get method and respective field (if exists))
        for (Method method : methods) {
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass.equals(Object.class)
                    || Collection.class.isAssignableFrom(declaringClass)) {
                continue;
            }

            if (isGetter(method)) {
                boolean hasField = false;
                for (Field field : fields) {
                    String name = getNameFromGetter(method);
                    if (field.getName().equalsIgnoreCase(name)) {
                        props.put(method, field);
                        hasField = true;
                        break;
                    }
                }
                if (!hasField) {
                    props.put(method, null);
                }
            }
        }
        return props;
    }

    private boolean isGetter(final Method method) {
        return method.getName().startsWith("get") || method.getName().startsWith("is");
    }

    private String getNameFromGetter(final Method getter) {
        String[] getterPrefixes = {"get", "is"};
        String methodName = getter.getName();
        String fieldName = null;
        for (String prefix : getterPrefixes) {
            if (methodName.startsWith(prefix)) {
                fieldName = methodName.substring(prefix.length());
            }
        }

        if (fieldName == null || "".equals(fieldName)) {
            return null;
        }

        fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
        return fieldName;
    }

    protected void setRequired(boolean required) {
        this.required = required;
    }

    protected void processAttributes(ObjectNode node, Class<?> type) {
        final Attributes attributes = type.getAnnotation(Attributes.class);
        if (attributes != null) {
            //node.put("$schema", SchemaVersion.DRAFTV4.getLocation().toString());
            if (!attributes.id().isEmpty()) {
                node.put("id", attributes.id());
            }
            if (!attributes.description().isEmpty()) {
                node.put("description", attributes.description());
            }
            if (!attributes.pattern().isEmpty()) {
                node.put("pattern", attributes.pattern());
            }
            if (!attributes.format().isEmpty()) {
                node.put("format", attributes.format());
            }
            if (!attributes.title().isEmpty()) {
                node.put("title", attributes.title());
            }
            if (attributes.maximum() > -1) {
                node.put("maximum", attributes.maximum());
            }
            if (attributes.exclusiveMaximum()) {
                node.put("exclusiveMaximum", true);
            }
            if (attributes.minimum() > -1) {
                node.put("minimum", attributes.minimum());
            }
            if (attributes.exclusiveMinimum()) {
                node.put("exclusiveMinimum", true);
            }
            if (attributes.enums().length > 0) {
                ArrayNode enumArray = node.putArray("enum");
                String[] enums = attributes.enums();
                for (String v : enums) {
                    enumArray.add(v);
                }
            }
            if (attributes.uniqueItems()) {
                node.put("uniqueItems", true);
            }
            if (attributes.minItems() > 0) {
                node.put("minItems", attributes.minItems());
            }
            if (attributes.maxItems() > -1) {
                node.put("maxItems", attributes.maxItems());
            }
            if (attributes.multipleOf() > 0) {
                node.put("multipleOf", attributes.multipleOf());
            }
            if (attributes.minLength() > 0) {
                node.put("minLength", attributes.minItems());
            }
            if (attributes.maxLength() > -1) {
                node.put("maxLength", attributes.maxItems());
            }
            if (attributes.required()) {
                setRequired(true);
            }
            if (attributes.readonly()) {
            	node.put("readonly", attributes.readonly());
            }
        }
    }
}

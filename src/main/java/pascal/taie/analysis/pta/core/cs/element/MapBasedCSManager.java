/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.pta.core.cs.element;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.MapUtils;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static pascal.taie.util.collection.MapUtils.newHybridMap;
import static pascal.taie.util.collection.MapUtils.newMap;

/**
 * Manages data by maintaining the data and their context-sensitive
 * counterparts by maps.
 */
public class MapBasedCSManager implements CSManager {

    private final Map<Var, Map<Context, CSVar>> vars = newMap();
    private final Map<CSObj, Map<JField, InstanceField>> instanceFields = newMap();
    private final Map<CSObj, ArrayIndex> arrayIndexes = newMap();
    private final Map<JField, StaticField> staticFields = newMap();
    private final Map<Obj, Map<Context, CSObj>> objs = newMap();
    private final Map<Invoke, Map<Context, CSCallSite>> callSites = newMap();
    private final Map<JMethod, Map<Context, CSMethod>> methods = newMap();

    private static <R, Key1, Key2> R getOrCreateCSElement(
            Map<Key1, Map<Key2, R>> map, Key1 key1, Key2 key2, BiFunction<Key1, Key2, R> creator) {
        return map.computeIfAbsent(key1, k -> newHybridMap())
                .computeIfAbsent(key2, (k) -> creator.apply(key1, key2));
    }

    @Override
    public CSVar getCSVar(Context context, Var var) {
        return getOrCreateCSElement(vars, Objects.requireNonNull(var), context,
                (v, c) -> initializePointsToSet(new CSVar(v, c)));
    }

    @Override
    public InstanceField getInstanceField(CSObj base, JField field) {
        return getOrCreateCSElement(instanceFields, base, field,
                (b, f) -> initializePointsToSet(new InstanceField(b, f)));
    }

    @Override
    public ArrayIndex getArrayIndex(CSObj array) {
        return arrayIndexes.computeIfAbsent(array,
                (a) -> initializePointsToSet(new ArrayIndex(a)));
    }

    @Override
    public StaticField getStaticField(JField field) {
        return staticFields.computeIfAbsent(field,
                (f) -> initializePointsToSet(new StaticField(f)));
    }

    @Override
    public CSObj getCSObj(Context heapContext, Obj obj) {
        return getOrCreateCSElement(objs, obj, heapContext, CSObj::new);
    }

    @Override
    public CSCallSite getCSCallSite(Context context, Invoke callSite) {
        return getOrCreateCSElement(callSites, callSite, context, CSCallSite::new);
    }

    @Override
    public CSMethod getCSMethod(Context context, JMethod method) {
        return getOrCreateCSElement(methods, method, context, CSMethod::new);
    }

    @Override
    public Stream<CSVar> csVars() {
        return MapUtils.mapMapValues(vars);
    }

    @Override
    public Stream<CSVar> csVarsOf(Var var) {
        return vars.getOrDefault(var, Map.of()).values().stream();
    }

    @Override
    public Stream<InstanceField> instanceFields() {
        return MapUtils.mapMapValues(instanceFields);
    }

    @Override
    public Stream<ArrayIndex> arrayIndexes() {
        return arrayIndexes.values().stream();
    }

    @Override
    public Stream<StaticField> staticFields() {
        return staticFields.values().stream();
    }

    @Override
    public Stream<CSObj> objects() {
        return MapUtils.mapMapValues(objs);
    }

    private <P extends Pointer> P initializePointsToSet(P pointer) {
        pointer.setPointsToSet(PointsToSetFactory.make());
        return pointer;
    }
}

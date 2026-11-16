package io.github.lachin12.oms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {
    @ParameterizedTest(name = "{0} -> {1}: allowed={2}")
    @MethodSource("allTransitions")
    void definesEveryPairOfStates(OrderStatus from, OrderStatus to, boolean allowed) {
        assertEquals(allowed, from.canTransitionTo(to));
    }

    static Stream<Arguments> allTransitions() {
        Map<OrderStatus, Set<OrderStatus>> allowed = new EnumMap<>(OrderStatus.class);
        allowed.put(OrderStatus.NEW, EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.ACCEPTED,
                EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.PARTIALLY_FILLED,
                EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED));
        return Stream.of(OrderStatus.values()).flatMap(from -> Stream.of(OrderStatus.values())
                .map(to -> Arguments.of(from, to, allowed.getOrDefault(from, Set.of()).contains(to))));
    }
}

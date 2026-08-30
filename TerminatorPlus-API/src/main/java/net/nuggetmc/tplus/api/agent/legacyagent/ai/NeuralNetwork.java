package net.nuggetmc.tplus.api.agent.legacyagent.ai;

import net.nuggetmc.tplus.compat.bungee.ChatColor;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainBank;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetworkGenetics;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;

import java.util.*;

public class NeuralNetwork {

    private final Map<BotNode, NodeConnections> nodes;

    private final boolean dynamicLR;
    private final MovementBrainBank movementBrainBank;

    public static final NeuralNetwork RANDOM = new NeuralNetwork(new HashMap<>());

    private NeuralNetwork(Map<BotNode, Map<BotDataType, Double>> profile) {
        this(profile, null);
    }

    private NeuralNetwork(Map<BotNode, Map<BotDataType, Double>> profile, MovementBrainBank movementBrainBank) {
        this.nodes = new HashMap<>();
        this.dynamicLR = true;
        this.movementBrainBank = movementBrainBank;

        if (profile == null) {
            for (BotNode node : BotNode.values()) {
                nodes.put(node, new NodeConnections());
            }
        } else {
            profile.forEach((nodeType, map) -> nodes.put(nodeType, new NodeConnections(map)));
        }
    }

    public static NeuralNetwork createNetworkFromProfile(Map<BotNode, Map<BotDataType, Double>> profile) {
        return new NeuralNetwork(profile);
    }

    public static NeuralNetwork createMovementControllerNetwork(MovementNetwork movementNetwork) {
        MovementBrainBank bank = MovementNetworkGenetics.isValid(movementNetwork)
                ? MovementBrainBank.singleFallback(movementNetwork,
                net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainPersistence.TrainingMetadata.manual(),
                "",
                "single-network")
                : null;
        return new NeuralNetwork(null, bank);
    }

    public static NeuralNetwork createMovementControllerNetwork(MovementBrainBank movementBrainBank) {
        return new NeuralNetwork(null, movementBrainBank);
    }

    public static NeuralNetwork generateRandomNetwork() {
        return new NeuralNetwork(null);
    }

    public NodeConnections fetch(BotNode node) {
        return nodes.get(node);
    }

    public boolean check(BotNode node) {
        return nodes.get(node).check();
    }

    public double value(BotNode node) {
        return nodes.get(node).value();
    }

    public void feed(BotData data) {
        nodes.values().forEach(n -> n.test(data));
    }

    public Map<BotNode, NodeConnections> nodes() {
        return nodes;
    }

    public boolean dynamicLR() {
        return dynamicLR;
    }

    public boolean usesMovementController() {
        return movementBrainBank != null && movementBrainBank.hasValidFallback();
    }

    public MovementNetwork movementNetwork() {
        return movementBrainBank == null ? null : movementBrainBank.fallbackNetwork();
    }

    public MovementBrainBank movementBrainBank() {
        return movementBrainBank;
    }

    public Map<BotNode, Map<BotDataType, Double>> values() {
        Map<BotNode, Map<BotDataType, Double>> output = new HashMap<>();
        nodes.forEach((nodeType, node) -> output.put(nodeType, node.getValues()));
        return output;
    }

    public static String join(Collection<String> collection) {
        StringBuilder sb = new StringBuilder();
        for (String s : collection) {
            sb.append(s).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    public String output() {
        List<String> strings = new ArrayList<>();
        nodes.forEach((type, node) -> strings.add(type.name().toLowerCase() + "=" + (node.check() ? ChatUtils.ON + "1" : ChatUtils.OFF + "0") + ChatColor.RESET));
        Collections.sort(strings);
        return "[" + join(strings) + "]";
    }

    @Override
    public String toString() {
        List<String> strings = new ArrayList<>();

        nodes.forEach((nodeType, node) -> {
            List<String> values = new ArrayList<>();
            values.add("name=\"" + nodeType.name().toLowerCase() + "\"");
            node.getValues().forEach((dataType, value) -> values.add(dataType.getShorthand() + "=" + MathUtils.round2Dec(value)));
            strings.add("{" + join(values) + "}");
        });

        return "NeuralNetwork{nodes:[" + join(strings) + "]}";
    }
}

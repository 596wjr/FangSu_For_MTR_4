package com.fangsu.train.selection;

import com.fangsu.mappings.ComponentHelper;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehicleCar;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.resource.VehicleResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrainCatalog {
    private static final Pattern CAR_ID = Pattern.compile("^(.+)_(cab|trailer)(?:_([a-z0-9]+(?:_[a-z0-9]+)*))?$", Pattern.CASE_INSENSITIVE);
    private final Map<String, Car> byId;
    private final List<Variant> variants;
    private final Map<String, Set<String>> tags;

    public TrainCatalog(TransportMode mode) {
        Map<String, VehicleResource> resources = new LinkedHashMap<>();
        CustomResourceLoader.iterateVehicles(mode, resource -> resources.put(resource.getId(), resource));
        Map<String, Map<String, Set<String>>> tagsByCar = new LinkedHashMap<>();
        tags = new TreeMap<>();
        CustomResourceLoader.getVehicleTags(mode).forEach((key, values) -> values.forEach((value, ids) -> {
            for (String id : ids) {
                if (!resources.containsKey(id)) continue;
                tags.computeIfAbsent(key, ignored -> new TreeSet<>()).add(value);
                tagsByCar.computeIfAbsent(id, ignored -> new TreeMap<>())
                        .computeIfAbsent(key, ignored -> new TreeSet<>()).add(value);
            }
        }));

        byId = new LinkedHashMap<>();
        Map<String, List<Car>> groups = new LinkedHashMap<>();
        resources.forEach((id, resource) -> {
            Map<String, Set<String>> carTags = tagsByCar.getOrDefault(id, Map.of());
            Role role = classify(id, carTags.getOrDefault("type", Set.of()));
            String name = resource.getName().getString();
            Car car = new Car(resource, name.isBlank() ? id : name, role, carTags);
            byId.put(id, car);
            groups.computeIfAbsent(role.prefix, ignored -> new ArrayList<>()).add(car);
        });
        variants = groups.entrySet().stream().map(entry -> buildVariant(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Variant::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Variant::prefix))
                .toList();
    }

    public Car find(String id) {
        return byId.get(id);
    }

    public Map<String, Set<String>> tags() {
        return tags;
    }

    public List<Variant> filter(String query, Map<String, Set<String>> filters) {
        String[] terms = query.isBlank() ? new String[0] : query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<Variant> visible = new ArrayList<>();
        for (Variant variant : variants) {
            List<Car> matches = variant.allCars.stream().filter(car -> matches(variant, car, terms, filters)).toList();
            if (!matches.isEmpty()) visible.add(new Variant(variant.prefix, variant.name, variant.subtitle, variant.allCars, matches));
        }
        return visible;
    }

    public static Role classify(String id) {
        return classify(id, Set.of());
    }

    private static Role classify(String id, Set<String> types) {
        Matcher matcher = CAR_ID.matcher(id);
        if (matcher.matches()) return role(matcher.group(1), matcher.group(2), matcher.group(3));
        for (String type : types) {
            Matcher fromTag = CAR_ID.matcher(id + "_" + type);
            if (fromTag.matches() && fromTag.group(1).equals(id)) return role(id, fromTag.group(2), fromTag.group(3));
        }
        return new Role(id, "", Shape.GENERIC, "", 5);
    }

    private static Role role(String prefix, String form, String suffix) {
        String code = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        String type = code.isEmpty() ? form.toLowerCase(Locale.ROOT) : form.toLowerCase(Locale.ROOT) + "_" + code;
        String label = code.substring(code.lastIndexOf('_') + 1).toUpperCase(Locale.ROOT);
        if (form.equalsIgnoreCase("trailer")) return new Role(prefix, type, Shape.TRAILER, label, 1);
        if (label.endsWith("1")) return new Role(prefix, type, Shape.FRONT, label, 0);
        if (label.endsWith("2")) return new Role(prefix, type, Shape.REAR, label, 2);
        return new Role(prefix, type, Shape.DOUBLE, code.equals("3") ? "" : label, 3);
    }

    private static Variant buildVariant(String prefix, List<Car> cars) {
        cars.sort(Comparator.comparingInt((Car car) -> car.role.order).thenComparing(Car::id));
        String name = commonName(cars, prefix);
        String family = cars.get(0).tags.getOrDefault("family", Set.of()).stream().findFirst().orElse("");
        String subtitle = prefix;
        if (!family.isBlank()) {
            String prefixKey = "_" + prefix.toLowerCase(Locale.ROOT) + "_";
            int at = prefixKey.indexOf("_" + family.toLowerCase(Locale.ROOT) + "_");
            if (at == 0 && prefix.equalsIgnoreCase(family)) {
                subtitle = ComponentHelper.translatable("ui.fangsu.train_selection.standard").getString();
            } else if (at >= 0) {
                String details = prefixKey.substring(0, at) + "_" + prefixKey.substring(at + family.length() + 2);
                subtitle = readableId(details);
            }
        }
        List<Car> all = List.copyOf(cars);
        return new Variant(prefix, name, subtitle, all, all);
    }

    private static String commonName(List<Car> cars, String prefix) {
        if (cars.size() == 1) return cars.get(0).name();
        String[] common = cars.get(0).name().split("\\s+");
        int size = common.length;
        for (Car car : cars) {
            String[] words = car.name().split("\\s+");
            int match = 0;
            while (match < size && match < words.length && common[match].equals(words[match])) match++;
            size = match;
        }
        return size == 0 ? readableId(prefix) : String.join(" ", List.of(common).subList(0, size));
    }

    private static String readableId(String id) {
        StringBuilder result = new StringBuilder();
        for (String part : id.replace(':', '_').split("_")) {
            if (part.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(switch (part.toLowerCase(Locale.ROOT)) {
                case "lcd", "led" -> part.toUpperCase(Locale.ROOT);
                case "small", "mini" -> ComponentHelper.translatable("ui.fangsu.train_selection." + part.toLowerCase(Locale.ROOT)).getString();
                default -> Character.toUpperCase(part.charAt(0)) + part.substring(1);
            });
        }
        return result.toString();
    }

    private static boolean matches(Variant variant, Car car, String[] terms, Map<String, Set<String>> filters) {
        for (Map.Entry<String, Set<String>> filter : filters.entrySet()) {
            if (filter.getValue().stream().noneMatch(car.tags.getOrDefault(filter.getKey(), Set.of())::contains)) return false;
        }
        StringBuilder search = new StringBuilder(variant.name).append(' ').append(variant.subtitle)
                .append(' ').append(car.id()).append(' ').append(car.name());
        car.tags.forEach((key, values) -> values.forEach(value -> search.append(' ').append(key).append(':').append(value)));
        String haystack = search.toString().toLowerCase(Locale.ROOT);
        for (String term : terms) if (!haystack.contains(term)) return false;
        return true;
    }

    public enum Shape { FRONT, TRAILER, REAR, DOUBLE, GENERIC }

    public record Role(String prefix, String type, Shape shape, String label, int order) {}

    public record Car(VehicleResource resource, String name, Role role, Map<String, Set<String>> tags) {
        public String id() { return resource.getId(); }

        public VehicleCar createCar() {
            return new VehicleCar(id(), resource.getLength(), resource.getWidth(), resource.getBogie1Position(),
                    resource.getBogie2Position(), resource.getCouplingPadding1(), resource.getCouplingPadding2());
        }
    }

    public record Variant(String prefix, String name, String subtitle, List<Car> allCars, List<Car> visibleCars) {}
}

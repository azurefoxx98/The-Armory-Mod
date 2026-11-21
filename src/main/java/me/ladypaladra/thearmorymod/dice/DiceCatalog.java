package me.ladypaladra.thearmorymod.dice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every die's shape, read from the data the art build writes beside the models.
 *
 * <p>The data is checked as it is read, because a wrong normal or turn makes a die land on one face and show another,
 * and nothing in the game would say so. Each face's turn must carry its normal straight up, every label must be one
 * its die can show, and each die type must have exactly its number of faces.</p>
 */
public final class DiceCatalog {

    /**
     * Where the build puts the data: beside these classes, where no game asset loader looks.
     */
    public static final String RESOURCE = "/me/ladypaladra/thearmorymod/dice/dice.json";

    private static final double UNIT_TOLERANCE = 1.0E-4;

    private final Map<String, DieShape> shapes;
    private final Map<String, DieColour> colours;
    private final Map<String, DieItem> items;
    private final Map<String, DieHull> hulls = new LinkedHashMap<>();

    private DiceCatalog(@Nonnull Map<String, DieShape> shapes, @Nonnull Map<String, DieColour> colours,
                        @Nonnull Map<String, DieItem> items) {
        this.shapes = Collections.unmodifiableMap(shapes);
        this.colours = Collections.unmodifiableMap(colours);
        this.items = Collections.unmodifiableMap(items);
    }

    /**
     * The dice item {@code itemId}, or null when the id is no die.
     */
    @Nullable
    public DieItem item(@Nullable String itemId) {
        return itemId != null ? items.get(itemId) : null;
    }

    @Nonnull
    public Map<String, DieItem> items() {
        return items;
    }

    @Nonnull
    public Map<String, DieColour> colours() {
        return colours;
    }

    /**
     * The colour a die of shape {@code shapeKey} thrown from item {@code itemId} wears. An id no longer in the data (a
     * die saved before its colour was removed) wears the colour its die is crafted in.
     */
    @Nonnull
    public DieColour colourOf(@Nullable String itemId, @Nonnull String shapeKey) {
        DieItem item = item(itemId);
        if (item == null) {
            for (DieType type : DieType.values()) {
                if (type.shapeKeys().contains(shapeKey)) {
                    item = items.get(type.itemId());
                    break;
                }
            }
        }
        if (item == null) {
            throw new IllegalArgumentException("No die shape " + shapeKey);
        }
        return item.colourOf(shapeKey);
    }

    /**
     * The solid the physics flies for {@code key}, built once when the data is read.
     */
    @Nonnull
    public DieHull hull(@Nonnull String key) {
        DieHull hull = hulls.get(key);
        if (hull == null) {
            throw new IllegalArgumentException("No die shape " + key);
        }
        return hull;
    }

    @Nonnull
    public static DiceCatalog load() throws IOException {
        try (InputStream in = DiceCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("The dice data is missing from the plugin: " + RESOURCE);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Nonnull
    public static DiceCatalog parse(@Nonnull Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonObject dice = root.getAsJsonObject("dice");
        Map<String, DieShape> shapes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : dice.entrySet()) {
            shapes.put(entry.getKey(), readShape(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
        Map<String, DieColour> colours = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("colours").entrySet()) {
            JsonObject c = entry.getValue().getAsJsonObject();
            JsonArray glow = c.getAsJsonArray("glow");
            colours.put(entry.getKey(), new DieColour(entry.getKey(), c.get("art").getAsString(),
                    c.get("light").getAsString(),
                    new DieShape.Rgb(glow.get(0).getAsInt(), glow.get(1).getAsInt(), glow.get(2).getAsInt()),
                    c.get("texture").getAsString(), c.get("darkTexture").getAsString()));
        }
        Map<String, DieItem> items = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("items").entrySet()) {
            JsonObject i = entry.getValue().getAsJsonObject();
            DieType type = DieType.valueOf(i.get("die").getAsString());
            List<DieColour> worn = new ArrayList<>();
            for (JsonElement key : i.getAsJsonArray("colours")) {
                DieColour colour = colours.get(key.getAsString());
                if (colour == null) {
                    throw new IllegalStateException(entry.getKey() + " wears " + key + ", which is no colour");
                }
                worn.add(colour);
            }
            items.put(entry.getKey(), new DieItem(entry.getKey(), type, List.copyOf(worn)));
        }
        DiceCatalog catalog = new DiceCatalog(shapes, colours, items);
        catalog.check();
        return catalog;
    }

    @Nonnull
    public DieShape shape(@Nonnull String key) {
        DieShape shape = shapes.get(key);
        if (shape == null) {
            throw new IllegalArgumentException("No die shape " + key);
        }
        return shape;
    }

    /**
     * The physical dice one throw of {@code type} sends out, in reading order.
     */
    @Nonnull
    public List<DieShape> shapesOf(@Nonnull DieType type) {
        List<DieShape> out = new ArrayList<>(type.shapeKeys().size());
        for (String key : type.shapeKeys()) {
            out.add(shape(key));
        }
        return out;
    }

    @Nonnull
    public Map<String, DieShape> shapes() {
        return shapes;
    }

    private static DieShape readShape(String key, JsonObject json) {
        List<DieShape.Face> faces = new ArrayList<>();
        JsonArray results = json.getAsJsonArray("results");
        for (int i = 0; i < results.size(); i++) {
            JsonObject face = results.get(i).getAsJsonObject();
            JsonArray n = face.getAsJsonArray("normal");
            JsonArray q = face.getAsJsonArray("restTurn");
            faces.add(new DieShape.Face(
                    i,
                    face.get("result").getAsString(),
                    new Vector3d(n.get(0).getAsDouble(), n.get(1).getAsDouble(), n.get(2).getAsDouble()),
                    // The data stores x, y, z, w, the order the art build's quaternions use.
                    new Quaterniond(q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(),
                            q.get(3).getAsDouble()),
                    face.get("restHeight").getAsDouble(),
                    face.get("restMiddle").getAsDouble(),
                    face.get("model").getAsString()
            ));
        }
        List<Vector3dc> corners = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("hull")) {
            JsonArray c = element.getAsJsonArray();
            corners.add(new Vector3d(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()));
        }
        return new DieShape(
                key,
                json.get("model").getAsString(),
                json.get("radius").getAsDouble(),
                List.copyOf(corners),
                List.copyOf(faces)
        );
    }

    private void check() {
        // Every item wears one colour per physical die it throws, and every die keeps its crafted item, the one its
        // recipe makes and every die saved before the colours came wears.
        for (DieItem item : items.values()) {
            if (item.colours().size() != item.type().shapeKeys().size()) {
                throw new IllegalStateException(item.itemId() + " wears " + item.colours().size() + " colours for "
                        + item.type().shapeKeys().size() + " dice");
            }
        }
        for (DieType type : DieType.values()) {
            DieItem crafted = items.get(type.itemId());
            if (crafted == null || crafted.type() != type) {
                throw new IllegalStateException(type + " has no crafted item " + type.itemId());
            }
            List<DieShape> dice = shapesOf(type);
            for (DieShape shape : dice) {
                int expected = expectedFaces(shape.key());
                if (shape.faces().size() != expected) {
                    throw new IllegalStateException(shape.key() + " has " + shape.faces().size()
                            + " faces, a " + shape.key() + " has " + expected);
                }
                if (shape.radius() <= 0) {
                    throw new IllegalStateException(shape.key() + " has no size");
                }
                // The solid the physics flies must be the die the art drew: as many corners as its kind has, and
                // reaching exactly as far as its radius.
                if (shape.corners().size() != expectedCorners(shape.key())) {
                    throw new IllegalStateException(shape.key() + " has " + shape.corners().size()
                            + " corners, a " + shape.key() + " has " + expectedCorners(shape.key()));
                }
                double reach = 0.0;
                for (Vector3dc corner : shape.corners()) {
                    reach = Math.max(reach, corner.length());
                }
                if (Math.abs(reach - shape.radius()) > 2.0E-3) {
                    throw new IllegalStateException(shape.key() + "'s corners reach " + reach + ", its radius is "
                            + shape.radius());
                }
                // The solid found from the corners must have the die's own faces: a D4 four, a D20 twenty, the D3 its
                // cube's six. A corner out of place would give a solid with other faces, and a die that lands wrongly.
                DieHull hull = DieHull.of(shape);
                int solidFaces = shape.key().equals("D4") ? 4 : expectedFaces(shape.key());
                if (hull.normals.length != solidFaces) {
                    throw new IllegalStateException(shape.key() + "'s corners make a solid of " + hull.normals.length
                            + " faces, not " + solidFaces);
                }
                // Every edge must be found too, or a die edge could pass through a block unseen: a closed solid has
                // as many edges as corners and faces together, less two.
                int edges = hull.corners.length + solidFaces - 2;
                if (hull.edges.length != edges) {
                    throw new IllegalStateException(shape.key() + "'s solid has " + hull.edges.length + " edges, not "
                            + edges);
                }
                hulls.put(shape.key(), hull);
                for (DieShape.Face face : shape.faces()) {
                    checkFace(shape, face);
                }
            }
            // Every combination of faces must read as a result of this die, so a label typo fails here, not in play.
            if (dice.size() == 1) {
                for (DieShape.Face face : dice.get(0).faces()) {
                    type.read(List.of(face.label()));
                }
            } else {
                for (DieShape.Face tens : dice.get(0).faces()) {
                    for (DieShape.Face units : dice.get(1).faces()) {
                        type.read(List.of(tens.label(), units.label()));
                    }
                }
            }
        }
    }

    private static void checkFace(DieShape shape, DieShape.Face face) {
        String where = shape.key() + " face " + face.index() + " (" + face.label() + ")";
        if (Math.abs(face.normal().length() - 1.0) > UNIT_TOLERANCE) {
            throw new IllegalStateException(where + ": its normal is not a unit vector");
        }
        if (Math.abs(face.restTurn().lengthSquared() - 1.0) > UNIT_TOLERANCE) {
            throw new IllegalStateException(where + ": its turn is not a rotation");
        }
        Vector3d up = face.restTurn().transform(new Vector3d(face.normal()));
        if (up.y < 1.0 - UNIT_TOLERANCE) {
            throw new IllegalStateException(where + ": its turn does not carry it straight up (" + up + ")");
        }
        if (face.restMiddle() <= 0 || face.restHeight() <= face.restMiddle()) {
            throw new IllegalStateException(where + ": its resting heights are not a die lying on the ground");
        }
    }

    private static int expectedCorners(String key) {
        return switch (key) {
            case "D3", "D6" -> 8;
            case "D4" -> 4;
            case "D8" -> 6;
            case "D10", "D100_Tens", "D100_Units", "D20" -> 12;
            default -> throw new IllegalStateException("Unknown die shape " + key);
        };
    }

    private static int expectedFaces(String key) {
        return switch (key) {
            case "D3", "D6" -> 6;
            case "D4" -> 4;
            case "D8" -> 8;
            case "D10", "D100_Tens", "D100_Units" -> 10;
            case "D20" -> 20;
            default -> throw new IllegalStateException("Unknown die shape " + key);
        };
    }
}

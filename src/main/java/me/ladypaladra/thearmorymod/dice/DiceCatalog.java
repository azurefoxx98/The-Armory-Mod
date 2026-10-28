package me.ladypaladra.thearmorymod.dice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
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

    private DiceCatalog(@Nonnull Map<String, DieShape> shapes) {
        this.shapes = Collections.unmodifiableMap(shapes);
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
        DiceCatalog catalog = new DiceCatalog(shapes);
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
        JsonArray glow = json.getAsJsonArray("glow");
        return new DieShape(
                key,
                json.get("colour").getAsString(),
                new DieShape.Rgb(glow.get(0).getAsInt(), glow.get(1).getAsInt(), glow.get(2).getAsInt()),
                json.get("model").getAsString(),
                json.get("radius").getAsDouble(),
                List.copyOf(faces)
        );
    }

    private void check() {
        for (DieType type : DieType.values()) {
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

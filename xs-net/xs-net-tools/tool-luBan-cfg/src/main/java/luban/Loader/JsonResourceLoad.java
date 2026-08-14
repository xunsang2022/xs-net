package luban.Loader;

import com.google.gson.JsonElement;

import java.io.*;

public class JsonResourceLoad implements IJsonLoader{
    private static final String RESOURCE_PATH = "cfg/luban/json/%s.json";
    @Override
    public JsonElement load(String file) throws IOException {
        String resourcePath = RESOURCE_PATH.formatted( file);
        // 读取resource文件
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return com.google.gson.JsonParser.parseReader(new InputStreamReader(is));
        }
    }

    public JsonElement loadFromFileSystem(String file) throws IOException {
        String filePath = String.format("cfg/%s.json", file);
        File jsonFile = new File(filePath);
        if (!jsonFile.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        try (FileReader reader = new FileReader(jsonFile)) {
            return com.google.gson.JsonParser.parseReader(reader);
        }
    }
}

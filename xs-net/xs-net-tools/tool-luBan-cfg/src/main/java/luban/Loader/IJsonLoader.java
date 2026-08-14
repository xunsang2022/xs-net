package luban.Loader;

import com.google.gson.JsonElement;

public interface IJsonLoader {
    JsonElement load(String file) throws java.io.IOException;
}

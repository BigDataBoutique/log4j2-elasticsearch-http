import com.bigdataboutique.logging.log4j2.ElasticsearchHttpProvider;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TagsParserTest {

    @Test
    public void testCustomField() {
        Map<String, List<String>> tags = ElasticsearchHttpProvider.createTagsMap("test:test");
        assertEquals(1, tags.size());
        assertEquals(1, tags.get("test").size());
        assertEquals("test", tags.get("test").get(0));
    }

    @Test
    public void testSingleTag() {
        Map<String, List<String>> tags = ElasticsearchHttpProvider.createTagsMap("test");
        assertEquals(1, tags.size());
        assertEquals(1, tags.get("tags").size());
        assertEquals("test", tags.get("tags").get(0));
    }

    @Test
    public void testMultipleTags() {
        Map<String, List<String>> tags = ElasticsearchHttpProvider.createTagsMap("foo,bar");
        assertEquals(1, tags.size());
        assertEquals(2, tags.get("tags").size());
        assertEquals("foo", tags.get("tags").get(0));
        assertEquals("bar", tags.get("tags").get(1));
    }

    @Test
    public void testMixedTags() {
        Map<String, List<String>> tags = ElasticsearchHttpProvider.createTagsMap("foo,test:bar");
        assertEquals(2, tags.size());
        assertEquals(1, tags.get("tags").size());
        assertEquals(1, tags.get("test").size());
        assertEquals("foo", tags.get("tags").get(0));
        assertEquals("bar", tags.get("test").get(0));
    }
}

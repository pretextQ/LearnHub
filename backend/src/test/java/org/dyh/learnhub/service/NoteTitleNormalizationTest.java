package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoteTitleNormalizationTest {
    @Test
    void preservesDifferentFirstHeadingsEvenWhenOneContainsTheOther() {
        for (String heading : new String[]{"Sample methods", "More Sample", "A Sample guide", "Sam", "Sample!"}) {
            String content = "# " + heading + "\r\n\r\nSynthetic body";
            assertEquals(content, NoteService.normalizeNoteContent(content, "Sample"));
        }
        assertEquals("## Sample\nBody", NoteService.normalizeNoteContent("## Sample\nBody", "Sample"));
    }

    @Test
    void stripsRepeatedDocumentTitleAndKeepsTheBodyUnchanged() {
        assertEquals("    indented code\r\n\r\nBody  \r\n",
                NoteService.normalizeNoteContent("# Sample #\r\n\r\n# Sample\r\n\r\n    indented code\r\n\r\nBody  \r\n", " Sample "));
    }

    @Test
    void collapsesOnlyAdjacentIdenticalHeadingsAtTheSameLevel() {
        String content = "## Sample notes\n## Sample\n### Sample\n### Sample\n### Sample\nBody\n### Sample\n";
        String expected = "## Sample notes\n## Sample\n### Sample\nBody\n### Sample\n";
        assertEquals(expected, NoteService.normalizeNoteContent(content, "Other"));
        String differentMath = "## $x-y$\n## $xy$\n";
        assertEquals(differentMath, NoteService.normalizeNoteContent(differentMath, "Other"));
    }

    @Test
    void preservesHeadingsInsideFencesMathIndentedCodeAndRawHtml() {
        String content = "    # Sample\n    # Sample\n\n"
                + "````text\n```\n## Sample\n## Sample\n````\n\n"
                + "~~~text\n```\n## Sample\n## Sample\n~~~\n\n"
                + "$$\n## Sample\n## Sample\n$$\n\n"
                + "\\[\n## Sample\n## Sample\n\\]\n\n"
                + "<div>\n## Sample\n## Sample\n</div>\n\n";
        assertEquals(content, NoteService.normalizeNoteContent(content, "Sample"));
        assertEquals(content + "## Sample\n", NoteService.normalizeNoteContent(content + "## Sample\n## Sample\n", "Other"));
    }
}

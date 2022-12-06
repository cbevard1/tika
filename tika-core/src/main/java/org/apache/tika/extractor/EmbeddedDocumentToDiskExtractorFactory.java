package org.apache.tika.extractor;

import org.apache.tika.config.Field;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

public class EmbeddedDocumentToDiskExtractorFactory implements EmbeddedDocumentExtractorFactory {
    private boolean writeFileNameToContent = true;
    private String extractDirRelativePath = "work";

    @Field
    public void setWriteFileNameToContent(boolean writeFileNameToContent) {
        this.writeFileNameToContent = writeFileNameToContent;
    }

    @Field
    public void setExtractDirRelativePath(String extractDirRelativePath) {
        this.extractDirRelativePath = extractDirRelativePath;
    }

    @Override
    public EmbeddedDocumentExtractor newInstance(Metadata metadata, ParseContext parseContext) {
        EmbeddedDocumentToDiskExtractor ex =
                new EmbeddedDocumentToDiskExtractor(parseContext);
        ex.setWriteFileNameToContent(writeFileNameToContent);
        ex.setExtractDir(extractDirRelativePath);
        return ex;
    }
}

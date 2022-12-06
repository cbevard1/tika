/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.extractor;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.io.IOUtils;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.CorruptedFileException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.FilenameUtils;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.parser.DelegatingParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;

import java.io.*;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.apache.tika.sax.XHTMLContentHandler.XHTML;

public class EmbeddedDocumentToDiskExtractor implements EmbeddedDocumentExtractor {

    private static final File ABSTRACT_PATH = new File("");

    private static final Parser DELEGATING_PARSER = new DelegatingParser();

    private boolean writeFileNameToContent = true;

    private final ParseContext context;

    private String extractDir = "work";

    private int embeddedFileCount = 0;

    private final Set<MediaType> unsupportedToDiskExtraction = Collections.singleton(MediaType.parse("message/rfc822"));

    public EmbeddedDocumentToDiskExtractor(ParseContext context) {
        this.context = context;
    }

    public boolean shouldParseEmbedded(Metadata metadata) {
        DocumentSelector selector = context.get(DocumentSelector.class);
        if (selector != null) {
            return selector.select(metadata);
        }

        FilenameFilter filter = context.get(FilenameFilter.class);
        if (filter != null) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name != null) {
                return filter.accept(ABSTRACT_PATH, name);
            }
        }

        return true;
    }

    public void parseEmbedded(
            InputStream tikaStream, ContentHandler handler, Metadata metadata, boolean outputHtml)
            throws SAXException, IOException {
        String embeddedFileName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        MediaType contentType = TikaConfig.getDefaultConfig().getDetector().detect(
                TikaInputStream.get(new CloseShieldInputStream(tikaStream), new TemporaryResources()), metadata);
        InputStream stream = null;

        // if the file type is one that we want to save to disk, write the stream to disk and return a new input stream
        if(!unsupportedToDiskExtraction.contains(contentType)) {
            stream = saveToDisk(tikaStream, embeddedFileName, metadata, contentType);
        } else {
            stream = tikaStream;
        }

        if (outputHtml) {
            AttributesImpl attributes = new AttributesImpl();
            attributes.addAttribute("", "class", "class", "CDATA", "package-entry");
            handler.startElement(XHTML, "div", "div", attributes);
        }

        if (writeFileNameToContent && embeddedFileName != null && embeddedFileName.length() > 0 && outputHtml) {
            handler.startElement(XHTML, "h1", "h1", new AttributesImpl());
            char[] chars = embeddedFileName.toCharArray();
            handler.characters(chars, 0, chars.length);
            handler.endElement(XHTML, "h1", "h1");
        }

        // Use the delegate parser to parse this entry
        try (TemporaryResources tmp = new TemporaryResources()) {
            final TikaInputStream newStream = TikaInputStream.get(new CloseShieldInputStream(stream), tmp);
            if (stream instanceof TikaInputStream) {
                final Object container = ((TikaInputStream) stream).getOpenContainer();
                if (container != null) {
                    newStream.setOpenContainer(container);
                }
            }

            DELEGATING_PARSER.parse(newStream, new EmbeddedContentHandler(new BodyContentHandler(handler)),
                    metadata, context);
        } catch (EncryptedDocumentException ede) {
            recordException(ede, context);
        } catch (CorruptedFileException e) {
            //necessary to stop the parse to avoid infinite loops
            //on corrupt sqlite3 files
            throw new IOException(e);
        } catch (TikaException e) {
            recordException(e, context);
        }

        if (outputHtml) {
            handler.endElement(XHTML, "div", "div");
        }
    }

    private InputStream saveToDisk(InputStream stream, String name, Metadata metadata, MediaType contentType) throws IOException {
        File outputFile = null;
        if (name == null) {
            name = "";
        }
        if(metadata.get("Multipart-Boundary") != null) {
            name = metadata.get("Multipart-Boundary") + "_attachment_" + embeddedFileCount + "_" + name;
        } else {
            name = UUID.randomUUID() + "_" + name;
        }

        InputStream toDiskStream = TikaInputStream.get(new CloseShieldInputStream(stream), new TemporaryResources());
        outputFile = getOutputFile(name, contentType);
        metadata.set("File-Location", outputFile.getAbsolutePath());

        File parent = outputFile.getParentFile();
        if (!parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("unable to create directory \"" + parent + "\"");
            }
        }
        System.out.println("Extracting '" + name + "' (" + contentType + ") to " + outputFile);

        try {
            FileOutputStream os = new FileOutputStream(outputFile);
            if (toDiskStream instanceof TikaInputStream) {
                TikaInputStream tin = (TikaInputStream) toDiskStream;

                if (tin.getOpenContainer() != null && tin.getOpenContainer() instanceof DirectoryEntry) {
                    POIFSFileSystem fs = new POIFSFileSystem();
                    copy((DirectoryEntry) tin.getOpenContainer(), fs.getRoot());
                    fs.writeFilesystem(os);
                } else {
                    IOUtils.copy(toDiskStream, os);
                }
            } else {
                IOUtils.copy(toDiskStream, os);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new FileInputStream(outputFile);
    }

    private void recordException(Exception e, ParseContext context) {
        ParseRecord record = context.get(ParseRecord.class);
        if (record == null) {
            return;
        }
        record.addException(e);
    }

    public Parser getDelegatingParser() {
        return DELEGATING_PARSER;
    }

    public void setWriteFileNameToContent(boolean writeFileNameToContent) {
        this.writeFileNameToContent = writeFileNameToContent;
    }

    public void setExtractDir(String extractDir) {
        this.extractDir = extractDir;
    }

    private File getOutputFile(String name, MediaType contentType) {
        String ext = getExtension(contentType);
        if (name.indexOf('.')==-1 && contentType!=null) {
            name += ext;
        }

        name = name.replaceAll("\u0000", " ");
        String normalizedName = FilenameUtils.normalize(name);
        File outputFile = new File(extractDir, normalizedName);

        return outputFile;
    }

    private String getExtension(MediaType contentType) {
        String extReturn = ".bin";
        try {
            String ext = TikaConfig.getDefaultConfig().getMimeRepository().forName(
                    contentType.toString()).getExtension();
            if (ext != null) extReturn = ext;
        } catch (MimeTypeException e) {
            e.printStackTrace();
        }
        return extReturn;

    }

    protected void copy(DirectoryEntry sourceDir, DirectoryEntry destDir)
            throws IOException {
        for (org.apache.poi.poifs.filesystem.Entry entry : sourceDir) {
            if (entry instanceof DirectoryEntry) {
                // Need to recurse
                DirectoryEntry newDir = destDir.createDirectory(entry.getName());
                copy((DirectoryEntry) entry, newDir);
            } else {
                // Copy entry
                InputStream contents = null;
                try {
                    contents = new DocumentInputStream((DocumentEntry) entry);
                    destDir.createDocument(entry.getName(), contents);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    contents.close();
                }
            }
        }
    }
}

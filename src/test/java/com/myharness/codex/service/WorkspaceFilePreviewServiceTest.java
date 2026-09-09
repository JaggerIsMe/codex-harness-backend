package com.myharness.codex.service;

import com.myharness.codex.config.WorkspacePreviewProperties;
import com.myharness.codex.entity.dto.WorkspaceFileSnapshotDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFilePreviewServiceTest {
    WorkspaceFileService files=mock(WorkspaceFileService.class);
    WorkspacePreviewProperties limits=new WorkspacePreviewProperties();
    WorkspaceFilePreviewService service=new WorkspaceFilePreviewService(files,limits,new com.myharness.codex.config.WorkspaceFileProperties());
    WorkspaceFilePreviewVO preview(String name,byte[] bytes) throws Exception {
        when(files.previewSnapshot(1L,2L,3L)).thenReturn(new WorkspaceFileSnapshotDTO("3",name,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),LocalDateTime.of(2026,9,8,10,0),
                new WorkspaceFileContentVO(new ByteArrayResource(bytes),name,bytes.length)));
        return service.preview(1L,2L,3L);
    }
    WorkspaceFilePreviewVO preview(String name,String text) throws Exception {return preview(name,text.getBytes(StandardCharsets.UTF_8));}
    @Test void detectsTextsTablesAndSourceOnlyActiveDocuments() throws Exception {
        assertEquals("TEXT",preview("source.unknown","中文\r\nhello").kind());
        assertEquals("TEXT",preview("empty.txt","").kind());
        assertEquals("MARKDOWN",preview("README.MD","# 标题").kind());
        assertEquals("TABLE",preview("data.csv","a,b\n1,2").kind());
        assertEquals("TABLE",preview("data.tsv","a\tb").kind());
        assertEquals("TEXT",preview("page.html","<script>alert(1)</script>").kind());
        assertEquals("TEXT",preview("image.svg","<svg onload='alert(1)'/>").kind());
        assertEquals("TEXT",preview("forged.html","%PDF-1.7").kind());
        assertEquals("PDF",preview("unknown.bin","%PDF-1.7").kind());
    }
    @Test void strictEncodingAndLimitsNeverReturnReplacementText() throws Exception {
        var utf16=preview("中文.txt","\ufeff中文".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("TEXT",utf16.kind());assertEquals("utf-16le",utf16.encoding());
        assertEquals("UNSUPPORTED",preview("bad.txt",new byte[]{(byte)0xc3,0x28}).kind());
        assertEquals("UNSUPPORTED",preview("binary.txt",new byte[]{0,1,2}).kind());
        limits.setMaxTextBytes(3);
        assertEquals("UNSUPPORTED",preview("large.txt","four").kind());
        limits.setMaxTextBytes(100);limits.setMaxLines(2);
        assertEquals("UNSUPPORTED",preview("long.txt","a\r\nb\nc").kind());
        limits.setMaxFileBytes(3);
        assertEquals("UNSUPPORTED",preview("big.pdf","%PDF-1.7").kind());
    }
    @Test void verifiesSnapshotDigestBeforeReturningContentMetadata() throws Exception {
        when(files.previewSnapshot(1L,2L,3L)).thenReturn(new WorkspaceFileSnapshotDTO("3","a.txt","0".repeat(64),null,
                new WorkspaceFileContentVO(new ByteArrayResource("hello".getBytes()),"a.txt",5)));
        assertThrows(BusinessException.class,()->service.preview(1L,2L,3L));
    }
    @Test void inspectsImageDimensionsAndRejectsOversizedCorruptOrActiveImages() throws Exception {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20,10,BufferedImage.TYPE_INT_RGB),"png",output);
        var png=output.toByteArray();
        var image=preview("photo.bin",png);
        assertEquals("IMAGE",image.kind());assertEquals("image/png",image.mediaType());
        assertEquals(20,image.width());assertEquals(10,image.height());
        assertEquals("UNSUPPORTED",preview("photo.svg",png).kind());
        limits.setMaxImagePixels(199);assertEquals("UNSUPPORTED",preview("large.png",png).kind());
        assertEquals("UNSUPPORTED",preview("broken.jpg",new byte[]{(byte)255,(byte)216,(byte)255}).kind());
        byte[] animated=new byte[30];System.arraycopy("RIFF".getBytes(),0,animated,0,4);System.arraycopy("WEBPVP8X".getBytes(),0,animated,8,8);animated[20]=2;
        assertEquals("UNSUPPORTED",preview("animated.webp",animated).kind());
        limits.setMaxImagePixels(200);
        output.reset();ImageIO.write(new BufferedImage(20,10,BufferedImage.TYPE_INT_RGB),"gif",output);
        byte[] gif=output.toByteArray();assertEquals("IMAGE",preview("static.gif",gif).kind());
        // Browser allocation follows the logical screen, not just the smaller first frame.
        gif[6]=(byte)255;gif[7]=(byte)255;gif[8]=(byte)255;gif[9]=(byte)255;
        assertEquals("UNSUPPORTED",preview("oversized-screen.gif",gif).kind());
    }
}

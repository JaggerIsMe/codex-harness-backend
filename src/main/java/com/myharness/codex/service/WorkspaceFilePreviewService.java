package com.myharness.codex.service;

import com.myharness.codex.config.WorkspacePreviewProperties;
import com.myharness.codex.config.WorkspaceFileProperties;
import com.myharness.codex.entity.dto.WorkspaceFileSnapshotDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.vo.WorkspaceFilePreviewVO;
import com.myharness.codex.exception.BusinessException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Detects a bounded, authorized snapshot without decoding pixels or converting documents. */
@Service
public class WorkspaceFilePreviewService {
    private final WorkspaceFileService files;
    private final WorkspacePreviewProperties limits;
    private final WorkspaceFileProperties transfer;
    public WorkspaceFilePreviewService(WorkspaceFileService files,WorkspacePreviewProperties limits,WorkspaceFileProperties transfer){
        this.files=files;this.limits=limits;this.transfer=transfer;
    }
    private int maxFileBytes(){return (int)Math.min(limits.getMaxFileBytes(),transfer.getMaxFileBytes());}

    public WorkspaceFilePreviewVO preview(Long pid,Long uid,Long id) throws IOException {
        var snapshot=files.previewSnapshot(pid,uid,id);
        if(snapshot.file().sizeBytes()>maxFileBytes()) return view(snapshot,"UNSUPPORTED",null,null,null,"超过预览大小限制");
        byte[] data;
        try(var input=snapshot.file().resource().getInputStream()){data=input.readNBytes(maxFileBytes()+1);}
        try {
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if(data.length!=snapshot.file().sizeBytes() || !sha.equals(snapshot.sha256()))
                throw new BusinessException(ErrorCode.CONFLICT,"预览副本已变化，请重新获取文件");
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        String name=snapshot.file().fileName().toLowerCase(Locale.ROOT);
        // Active document extensions always remain source-only, even with a forged image header.
        if(!name.endsWith(".html") && !name.endsWith(".htm") && !name.endsWith(".svg")) {
            if(starts(data,"%PDF-")) return view(snapshot,"PDF","application/pdf",null,null,null);
            String media=imageType(data);
            if(media!=null) {
                try {
                    int[] size=dimensions(data,media);
                    if(size[0]<=0 || size[1]<=0 || (long)size[0]*size[1]>limits.getMaxImagePixels())
                        return view(snapshot,"UNSUPPORTED",media,null,null,"图片尺寸超过预览限制");
                    return view(snapshot,"IMAGE",media,null,size,null);
                } catch(IOException|RuntimeException e){return view(snapshot,"UNSUPPORTED",media,null,null,"图片格式损坏、为动画或无法安全读取尺寸，请下载查看");}
            }
        }
        if(data.length>Math.min(limits.getMaxTextBytes(),maxFileBytes())) return view(snapshot,"UNSUPPORTED",null,null,null,"文本超过预览大小限制或属于不支持的格式");
        String encoding="utf-8";int offset=0;
        if(data.length>=2 && data[0]==(byte)0xff && data[1]==(byte)0xfe){encoding="utf-16le";offset=2;}
        else if(data.length>=2 && data[0]==(byte)0xfe && data[1]==(byte)0xff){encoding="utf-16be";offset=2;}
        else if(data.length>=3 && data[0]==(byte)0xef && data[1]==(byte)0xbb && data[2]==(byte)0xbf) offset=3;
        try {
            String text=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,data.length-offset)).toString();
            if(text.codePoints().anyMatch(c -> c<32 && c!='\n' && c!='\r' && c!='\t' || c==127))
                return view(snapshot,"UNSUPPORTED",null,null,null,"暂不支持此二进制格式");
            if(text.split("\\r\\n|\\r|\\n",-1).length>limits.getMaxLines()) return view(snapshot,"UNSUPPORTED",null,null,null,"文本行数超过预览限制");
            String kind=name.endsWith(".md")||name.endsWith(".markdown")?"MARKDOWN":name.endsWith(".csv")||name.endsWith(".tsv")?"TABLE":"TEXT";
            return view(snapshot,kind,"text/plain",encoding,null,null);
        } catch(CharacterCodingException e){return view(snapshot,"UNSUPPORTED",null,null,null,"文件不是支持的文本编码或属于二进制格式，请下载查看");}
    }

    private WorkspaceFilePreviewVO view(WorkspaceFileSnapshotDTO s,String kind,String media,String encoding,int[] size,String reason) {
        boolean text=Set.of("TEXT","MARKDOWN","TABLE").contains(kind);
        return new WorkspaceFilePreviewVO(s.operationId(),s.path(),s.file().fileName(),kind,media,encoding,s.file().sizeBytes(),s.sha256(),
                s.readyAt()==null?null:s.readyAt().toString(),size==null?null:size[0],size==null?null:size[1],reason,
                new WorkspaceFilePreviewVO.Limits(text?Math.min(limits.getMaxTextBytes(),maxFileBytes()):maxFileBytes(),
                        limits.getMaxLines(),limits.getMaxRows(),limits.getMaxColumns(),"PDF".equals(kind)?limits.getMaxPdfPixels():limits.getMaxImagePixels()));
    }
    private static boolean starts(byte[] data,String value){return at(data,0,value);}
    private static boolean at(byte[] data,int offset,String value){
        if(offset<0 || data.length-offset<value.length()) return false;
        for(int i=0;i<value.length();i++) if((data[offset+i]&255)!=value.charAt(i)) return false;
        return true;
    }
    private static String imageType(byte[] data){
        if(starts(data,"\u0089PNG\r\n\u001a\n")) return "image/png";
        if(data.length>=3 && data[0]==(byte)255 && data[1]==(byte)216 && data[2]==(byte)255) return "image/jpeg";
        if(starts(data,"GIF87a")||starts(data,"GIF89a")) return "image/gif";
        if(starts(data,"RIFF")&&at(data,8,"WEBP")) return "image/webp";
        return null;
    }
    private static int le24(byte[] b,int i){return (b[i]&255)|((b[i+1]&255)<<8)|((b[i+2]&255)<<16);}
    private static int[] dimensions(byte[] data,String media) throws IOException {
        if(media.equals("image/webp")) return webpDimensions(data);
        if(media.equals("image/png")) {
            for(int i=8;i<=data.length-12;){
                long len=Integer.toUnsignedLong(ByteBuffer.wrap(data,i,4).getInt());
                if(len>data.length-i-12) throw new IOException();
                if(at(data,i+4,"acTL")) throw new IOException("animated");
                i+=(int)len+12;
            }
        }
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(data))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw new IOException();
            var reader=readers.next();
            try {
                reader.setInput(input);
                if(media.equals("image/gif")) {
                    if(reader.getNumImages(true)!=1 || data.length<10) throw new IOException("animated");
                    int width=(data[6]&255)|((data[7]&255)<<8), height=(data[8]&255)|((data[9]&255)<<8);
                    if(reader.getWidth(0)>width || reader.getHeight(0)>height) throw new IOException();
                    return new int[]{width,height};
                }
                return new int[]{reader.getWidth(0),reader.getHeight(0)};
            } finally {reader.dispose();}
        }
    }
    private static int[] webpDimensions(byte[] data) throws IOException {
        if(data.length<20 || Integer.toUnsignedLong(ByteBuffer.wrap(data,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt())!=data.length-8)
            throw new IOException();
        int[] canvas=null,image=null;
        for(int i=12;i<data.length;) {
            if(data.length-i<8) throw new IOException();
            long length=Integer.toUnsignedLong(ByteBuffer.wrap(data,i+4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            if(length>data.length-i-8) throw new IOException();
            int p=i+8;
            if(at(data,i,"ANIM")||at(data,i,"ANMF")) throw new IOException("animated");
            if(at(data,i,"VP8X")) {
                if(i!=12 || length!=10 || (data[p]&2)!=0) throw new IOException();
                canvas=new int[]{1+le24(data,p+4),1+le24(data,p+7)};
            } else if(at(data,i,"VP8 ") || at(data,i,"VP8L")) {
                if(image!=null) throw new IOException();
                if(at(data,i,"VP8 ")) {
                    if(length<10 || !at(data,p+3,"\u009d\u0001\u002a")) throw new IOException();
                    image=new int[]{((data[p+6]&255)|((data[p+7]&255)<<8))&0x3fff,((data[p+8]&255)|((data[p+9]&255)<<8))&0x3fff};
                } else {
                    if(length<5 || data[p]!=0x2f) throw new IOException();
                    int bits=ByteBuffer.wrap(data,p+1,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    image=new int[]{(bits&0x3fff)+1,((bits>>>14)&0x3fff)+1};
                }
            }
            i=p+(int)length+((int)length&1);
            if(i>data.length) throw new IOException();
        }
        if(image==null || canvas!=null && !Arrays.equals(canvas,image)) throw new IOException();
        return image;
    }
}

package com.myharness.codex.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component @Validated @ConfigurationProperties(prefix="harness.workspace-preview")
public class WorkspacePreviewProperties {
    @Min(1) @Max(20971520) private int maxTextBytes=1048576;
    @Min(1) @Max(20971520) private int maxFileBytes=20971520;
    @Min(1) @Max(20000) private int maxLines=20000;
    @Min(1) @Max(1000) private int maxRows=1000;
    @Min(1) @Max(100) private int maxColumns=100;
    @Min(1) @Max(20000000) private int maxImagePixels=20000000;
    @Min(1) @Max(4000000) private int maxPdfPixels=4000000;
    public int getMaxTextBytes(){return maxTextBytes;} public void setMaxTextBytes(int v){maxTextBytes=v;}
    public int getMaxFileBytes(){return maxFileBytes;} public void setMaxFileBytes(int v){maxFileBytes=v;}
    public int getMaxLines(){return maxLines;} public void setMaxLines(int v){maxLines=v;}
    public int getMaxRows(){return maxRows;} public void setMaxRows(int v){maxRows=v;}
    public int getMaxColumns(){return maxColumns;} public void setMaxColumns(int v){maxColumns=v;}
    public int getMaxImagePixels(){return maxImagePixels;} public void setMaxImagePixels(int v){maxImagePixels=v;}
    public int getMaxPdfPixels(){return maxPdfPixels;} public void setMaxPdfPixels(int v){maxPdfPixels=v;}
}

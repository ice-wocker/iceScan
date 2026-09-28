package com.ice.scan.data;

/** 文档里的一页：落在磁盘上的一个 JPEG 文件 + 它是用哪种效果做的。 */
public final class Page {

    /** 相对文档目录的文件名，例如 {@code p0.jpg}。 */
    public final String file;
    /** 处理时用的增强档位，取值见 {@code com.ice.scan.img.Enhance}。 */
    public final int mode;
    public final int width;
    public final int height;

    public Page(String file, int mode, int width, int height) {
        this.file = file;
        this.mode = mode;
        this.width = width;
        this.height = height;
    }
}
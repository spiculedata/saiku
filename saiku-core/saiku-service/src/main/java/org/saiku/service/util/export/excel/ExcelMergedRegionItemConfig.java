package org.saiku.service.util.export.excel;

/**
 * Created with IntelliJ IDEA.
 * User: sramazzina
 * Date: 22/06/12
 * Time: 9.52
 * To change this template use File | Settings | File Templates.
 */
public class ExcelMergedRegionItemConfig {

    private int startX;
    private int startY;
    private int width;
    private int height;

    public ExcelMergedRegionItemConfig () {
    }

    public ExcelMergedRegionItemConfig(int startX, int startY, int width, int height) {
        this.startX = startX;
        this.startY = startY;
        this.width = width;
        this.height = height;
    }

    public int getStartX() {
        return startX;
    }

    public void setStartX(int startX) {
        this.startX = startX;
    }

    public int getStartY() {
        return startY;
    }

    public void setStartY(int startY) {
        this.startY = startY;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ExcelMergedRegionItemConfig that = (ExcelMergedRegionItemConfig) o;

        if (height != that.height) return false;
        if (startX != that.startX) return false;
        if (startY != that.startY) return false;
        if (width != that.width) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = startX;
        result = 31 * result + startY;
        result = 31 * result + width;
        result = 31 * result + height;
        return result;
    }
}

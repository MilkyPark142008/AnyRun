package com.zcshou.utils;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.MapTileIndex;

/**
 * 瓦片地图源定义。
 *
 * <p><b>坐标顺序（很重要）</b>：不同瓦片服务的 URL 顺序并不一样，用错会表现为"地图能出来但瓦片错位拼贴"。</p>
 * <ul>
 *   <li>osmdroid 内置的 {@link XYTileSource#getTileURLString(long)} 拼的是
 *       {@code baseUrl + z + "/" + x + "/" + y + 扩展名}，也就是 <b>Z/X/Y</b>；
 *       这正好符合 OpenStreetMap 的规则，所以 OSM 直接用 {@link XYTileSource} 即可。</li>
 *   <li>Esri ArcGIS REST 的规则是 <b>Z/Y/X</b>，与上面相反，因此必须像
 *       {@link ZyxTileSource} 那样重写 {@code getTileURLString}。</li>
 * </ul>
 *
 * <p><b>坐标系</b>：OSM 与 Esri World Imagery 的瓦片都基于 WGS-84（Web Mercator / EPSG:3857），
 * 而原工程内部沿用的是 BD-09，转换在显示层完成，参见 {@link MapUtils}。</p>
 */
public final class TileSourceUtils {

    /** Esri World Imagery 卫星影像瓦片服务（瓦片顺序 Z/Y/X） */
    public static final String ESRI_WORLD_IMAGERY_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/";

    /**
     * OpenStreetMap 标准地图瓦片服务。
     * 这里用的是 OSM 德国镜像（全球覆盖、标准 OSM Carto 样式），因为官方主站
     * {@code https://tile.openstreetmap.org/} 在国内多数网络下不可达。
     * 如果你的网络能访问官方主站，把下面这行换成 {@link #OSM_TILE_URL_OFFICIAL} 即可。
     */
    public static final String OSM_TILE_URL = "https://tile.openstreetmap.de/";

    /** OpenStreetMap 官方主站瓦片地址（国内多数网络超时） */
    public static final String OSM_TILE_URL_OFFICIAL = "https://tile.openstreetmap.org/";

    /** OpenStreetMap 标准地图瓦片源（Z/X/Y，与 osmdroid 的 XYTileSource 一致） */
    public static final XYTileSource OSM_STANDARD = new XYTileSource(
            "OSMStandard",
            0, 19, 256, ".png",
            new String[]{OSM_TILE_URL},
            "© OpenStreetMap contributors");

    /** Esri World Imagery 卫星影像瓦片源（Z/Y/X，影像瓦片为 jpg） */
    public static final OnlineTileSourceBase ESRI_WORLD_IMAGERY = new ZyxTileSource(
            "EsriWorldImagery",
            0, 19, 256, ".jpg",
            new String[]{ESRI_WORLD_IMAGERY_URL},
            "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community");

    /**
     * ArcGIS REST 风格的在线瓦片源：URL 顺序为 {@code Z/Y/X}。
     *
     * <p>osmdroid 自带的 {@link XYTileSource} 拼的是 {@code Z/X/Y}，直接拿它去请求
     * Esri 会把经纬度方向弄反，出现"瓦片错位"的现象，所以这里单独重写。</p>
     */
    public static class ZyxTileSource extends OnlineTileSourceBase {

        public ZyxTileSource(String aName, int aZoomMinLevel, int aZoomMaxLevel,
                             int aTileSizePixels, String aImageFilenameEnding,
                             String[] aBaseUrl, String copyright) {
            super(aName, aZoomMinLevel, aZoomMaxLevel, aTileSizePixels,
                    aImageFilenameEnding, aBaseUrl, copyright);
        }

        @Override
        public String getTileURLString(long pMapTileIndex) {
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex)
                    + mImageFilenameEnding;
        }
    }

    private TileSourceUtils() {
    }
}

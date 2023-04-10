/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.sptx2;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import java.io.FileWriter;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumPixelSizeCalibration extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumPixelSizeCalibration.class);
	
	final private StringProperty xnumPxlSzCalXenOutFldrProp = PathPrefs.createPersistentPreference("xnumPxlSzCalXenOutFldr", ""); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	final private DoubleProperty xnumPxlSzCalDapiImgWidthProp = PathPrefs.createPersistentPreference("xnumPxlSzCalDapiImgWidth", 4096.0); // 7525.9,
	final private DoubleProperty xnumPxlSzCalDapiImgHeightProp = PathPrefs.createPersistentPreference("xnumPxlSzCalDapiImgHeight", 3072.0); // 5477.82
	final private DoubleProperty xnumPxlSzCalDapiImgPxlSizeProp = PathPrefs.createPersistentPreference("xnumPxlSzCalDapiImgPxlSize", 1.0); // 0.2125
	final private StringProperty xnumPxlSzCalAffineMtxProp = PathPrefs.createPersistentPreference("xnumPxlSzCalAffineMtx", "1.0, 0.0, 0.0, 0.0, 1.0, 0.0"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumPixelSizeCalibration() {
		params = new ParameterList()
			// .addTitleParameter(lastResults)
//			.addTitleParameter("10X Xenium Pixel Size Calibration")
//			.addDoubleParameter("dapiImgWidth", "DAPI Image Width", 7525.9, GeneralTools.micrometerSymbol(), "DAPI Image Width")			
//			.addDoubleParameter("dapiImgHeight", "DAPI Image Height", 5477.82, GeneralTools.micrometerSymbol(), "DAPI Image Height")			
//			.addDoubleParameter("dapiImgPxlSize", "DAPI Image Pixel Size", 0.2125, GeneralTools.micrometerSymbol(), "Spot Diameter")			
//			.addStringParameter("affineMtx", "Affine Matrix", "0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304", "Affine Matrix")
			// .addTitleParameter("10X Xenium Pixel Size Calibration")
			.addStringParameter("xeniumOutFolder", "Xenium output folder", xnumPxlSzCalXenOutFldrProp.get(), "Xenium output folder")
			.addDoubleParameter("dapiImgWidth", "DAPI image width", xnumPxlSzCalDapiImgWidthProp.get(), GeneralTools.micrometerSymbol(), "DAPI image width")			
			.addDoubleParameter("dapiImgHeight", "DAPI image height", xnumPxlSzCalDapiImgHeightProp.get(), GeneralTools.micrometerSymbol(), "DAPI image height")			
			.addDoubleParameter("dapiImgPxlSize", "DAPI image pixel size", xnumPxlSzCalDapiImgPxlSizeProp.get(), GeneralTools.micrometerSymbol(), "Spot diameter")			
			.addStringParameter("affineMtx", "Affine matrix", xnumPxlSzCalAffineMtxProp.get(), "Affine matrix")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			
			xnumPxlSzCalDapiImgWidthProp.set(params.getDoubleParameterValue("dapiImgWidth"));
			xnumPxlSzCalDapiImgHeightProp.set(params.getDoubleParameterValue("dapiImgHeight")); 
			xnumPxlSzCalDapiImgPxlSizeProp.set(params.getDoubleParameterValue("dapiImgPxlSize")); 
			xnumPxlSzCalAffineMtxProp.set(params.getStringParameterValue("affineMtx"));
			
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				final double dapiImageWidthMicrons = params.getDoubleParameterValue("dapiImgWidth");
				final double dapiImageHeightMicrons = params.getDoubleParameterValue("dapiImgHeight");
				final double dapiImagePixelSizeMicrons = params.getDoubleParameterValue("dapiImgPxlSize");
				final String affineMtxStr = params.getStringParameterValue("affineMtx");
	            final Double[] affineMtx = Arrays.stream(affineMtxStr.split(","))
	            		.map(Double::parseDouble)
	            		.toArray(Double[]::new);
				
	            
			        
	        	final double d0X = affineMtx[0] * 0.0 + affineMtx[1] * 0.0 + affineMtx[2] * 1.0;
	        	final double d0Y = affineMtx[3] * 0.0 + affineMtx[4] * 0.0 + affineMtx[5] * 1.0;
	        	
	        	final double dXX = affineMtx[0] * 1.0 + affineMtx[1] * 0.0 + affineMtx[2] * 1.0;
	        	final double dXY = affineMtx[3] * 1.0 + affineMtx[4] * 0.0 + affineMtx[5] * 1.0;
	        	final double dX = dapiImagePixelSizeMicrons*(new Point2D(d0X, d0Y).distance(new Point2D(dXX, dXY)));
	        	
	        	final double dYX = affineMtx[0] * 0.0 + affineMtx[1] * 1.0 + affineMtx[2] * 1.0;
	        	final double dYY = affineMtx[3] * 0.0 + affineMtx[4] * 1.0 + affineMtx[5] * 1.0;
	        	final double dY = dapiImagePixelSizeMicrons*(new Point2D(d0X, d0Y).distance(new Point2D(dYX, dYY)));
	        	
	        	
	        	ImageServerMetadata metadataNew = new ImageServerMetadata.Builder(server.getMetadata())
	        			.pixelSizeMicrons(dX, dY)
	    				.build();
	    		
	        	if (!server.getMetadata().equals(metadataNew)) imageData.updateServerMetadata(metadataNew);
	        
		        
		       
	        	
	        	
	        	
	        	
	        	// JSON object. Key value pairs are unordered. JSONObject supports java.util.Map interface.
	            final JSONObject jsonObj = new JSONObject();
	            jsonObj.put("dapi_width", dapiImageWidthMicrons);
	            jsonObj.put("dapi_height", dapiImageHeightMicrons);
	            jsonObj.put("dapi_pixel_size", dapiImagePixelSizeMicrons);
	            final JSONArray jsonAffineMatrix = new JSONArray();
	            Arrays.asList(affineMtx).forEach(v -> jsonAffineMatrix.put(v));
	            jsonObj.put("affine_matrix", jsonAffineMatrix);
	            
	            try {
	                // Constructs a FileWriter given a file name, using the platform's default charset
	            	final String xnumOutFldr = params.getStringParameterValue("xeniumOutFolder");
	            	final String affineMtxFilePath = Paths.get(xnumOutFldr, "affine_matrix.json").toString();
	                final FileWriter file = new FileWriter(affineMtxFilePath);
	                file.write(jsonObj.toString());
	                file.flush();
                    file.close();
	            } catch (IOException e) {
	                e.printStackTrace();
	            }
	            
	            
		        
			}
			catch(Exception e) {	
				lastResults =  "Something went wrong: "+e.getMessage();
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				lastResults =  "Interrupted!";
			}
			
			return resultPathObjectList;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(final PluginRunner<BufferedImage> runner) {	
		PathObjectHierarchy hierarchy = getHierarchy(runner);
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}

}

/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2022 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.commands;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.SortedList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TableView.TableViewSelectionModel;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.charts.HistogramDisplay;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.measure.PathTableData;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.gui.viewer.PathHierarchyPaintingHelper;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionModel;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.regions.RegionRequest;


/**
 * Show a summary table for an object of a particular type (annotation, detection, TMA core...)
 * 
 * @author Pete Bankhead
 */
public class SummaryMeasurementTableCommand {

	private final static Logger logger = LoggerFactory.getLogger(SummaryMeasurementTableCommand.class);

	private QuPathGUI qupath;
	
	private BooleanProperty showThumbnailsProperty;
	
	/**
	 * Command to show a summary measurement table, for PathObjects of a specified type (e.g. annotation, detection).
	 * @param qupath
	 */
	public SummaryMeasurementTableCommand(final QuPathGUI qupath) {
		super();
		this.qupath = qupath;
	}

	/**
	 * Show a measurement table for the specified image data.
	 * @param imageData the image data
	 * @param type the object type to show
	 */
	public void showTable(ImageData<BufferedImage> imageData, Class<? extends PathObject> type) {
		if (imageData == null) {
			Dialogs.showNoImageError("Show measurement table");
			return;
		}
		
		if (showThumbnailsProperty == null)
			showThumbnailsProperty = new SimpleBooleanProperty(PathPrefs.showMeasurementTableThumbnailsProperty().get());

		final PathObjectHierarchy hierarchy = imageData.getHierarchy();

		ObservableMeasurementTableData model = new ObservableMeasurementTableData();
		model.setImageData(imageData, imageData == null ? Collections.emptyList() : imageData.getHierarchy().getObjects(null, type));

		SplitPane splitPane = new SplitPane();
		HistogramDisplay histogramDisplay = new HistogramDisplay(model, true);

		//		table.setTableMenuButtonVisible(true);
		TableView<PathObject> table = new TableView<>();
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<PathObject>() {
			@Override
			public void onChanged(ListChangeListener.Change<? extends PathObject> c) {
				synchronizeSelectionModelToTable(hierarchy, c, table);
			}
		});
		StringProperty displayedName = new SimpleStringProperty(ServerTools.getDisplayableImageName(imageData.getServer()));
		var title = Bindings.createStringBinding(() -> {
			if (type == null)
				return "Results " + displayedName.get();
			else
				return PathObjectTools.getSuitableName(type, false) + " results - " + displayedName.get();			
		}, displayedName);
		

		// Handle double-click as a way to center on a ROI
		var centerCode = new KeyCodeCombination(KeyCode.SPACE);
		table.setRowFactory(params -> {
			var row = new TableRow<PathObject>();
			row.setOnMouseClicked(e -> {
				if (e.getClickCount() == 2) {
					maybeCenterROI(row.getItem());
				}
			});
			return row;
		});
		table.setOnKeyPressed(e -> {
			if (centerCode.match(e)) {
				var selected = table.getSelectionModel().getSelectedItem();
				if (selected != null)
					maybeCenterROI(selected);
			}
		});
				
		// Add a column to display images thumbnails
		TableColumn<PathObject, PathObject> colThumbnails = new TableColumn<>("Image");
		colThumbnails.setCellValueFactory(val -> new SimpleObjectProperty<>(val.getValue()));
		colThumbnails.visibleProperty().bind(showThumbnailsProperty);
		double padding = 10;
		var viewer = qupath.getViewers().stream().filter(v -> v.getImageData() == imageData).findFirst().orElse(null);
		colThumbnails.setCellFactory(column -> new TMACoreTableCell(imageData.getServer(), padding, viewer, true));
//			col.widthProperty().addListener((v, o, n) -> table.refresh());
//		colThumbnails.setMaxWidth(maxWidth + padding*2);
		table.getColumns().add(colThumbnails);
		
		// Have fewer histogram bins if we have TMA cores (since there aren't usually very many)
		boolean tmaCoreList = TMACoreObject.class.isAssignableFrom(type);
		if (tmaCoreList)
			histogramDisplay.setNumBins(10);			
			
		// Create numeric columns
		for (String columnName : model.getAllNames()) {
			// Add column
			if (model.isStringMeasurement(columnName)) {
				TableColumn<PathObject, String> col = new TableColumn<>(columnName);
				col.setCellValueFactory(column -> model.createStringMeasurement(column.getValue(), column.getTableColumn().getText()));
				col.setCellFactory(column -> new BasicTableCell<>());
				table.getColumns().add(col);			
			} else {
				TableColumn<PathObject, Number> col = new TableColumn<>(columnName);
				col.setCellValueFactory(column -> model.createNumericMeasurement(column.getValue(), column.getTableColumn().getText()));
				col.setCellFactory(column -> new NumericTableCell<PathObject>(histogramDisplay));
				table.getColumns().add(col);			
			}
		}


		// Set the PathObjects - need to deal with sorting, since a FilteredList won't handle it directly
		SortedList<PathObject> items = new SortedList<>(model.getItems());
		items.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(items);


		// Add buttons at the bottom
		List<ButtonBase> buttons = new ArrayList<>();
		
		ToggleButton btnHistogram = new ToggleButton("Show histograms");
		btnHistogram.selectedProperty().addListener((v, o, n) -> {
			if (n) {
				Pane paneHistograms = histogramDisplay.getPane();
				splitPane.getItems().add(paneHistograms);
			} else if (histogramDisplay != null)
				splitPane.getItems().remove(histogramDisplay.getPane());
		});
		buttons.add(btnHistogram);

//		Button btnScatterplot = new Button("Show scatterplots");
//		btnScatterplot.setOnAction(e -> {
//			SwingUtilities.invokeLater(() -> {
//				JDialog dialog = new ScatterplotDisplay(null, "Scatterplots: " + displayedName, model).getDialog();
//				dialog.setLocationRelativeTo(null);
//				dialog.setVisible(true);
//			});
//		});
//		buttons.add(btnScatterplot);
		
		
		Button btnCopy = new Button("Copy to clipboard");
		btnCopy.setOnAction(e -> {
			// TODO: Deal with repetition immediately below...
			Set<String> excludeColumns = new HashSet<>();
			for (TableColumn<?, ?> col : table.getColumns()) {
				if (!col.isVisible())
					excludeColumns.add(col.getText());
			}
			copyTableContentsToClipboard(model, excludeColumns);
		});
		buttons.add(btnCopy);

		Button btnSave = new Button("Save");
		btnSave.setOnAction(e -> {
			Set<String> excludeColumns = new HashSet<>();
			for (TableColumn<?, ?> col : table.getColumns()) {
				if (!col.isVisible())
					excludeColumns.add(col.getText());
			}
			File fileOutput = promptForOutputFile();
			if (fileOutput == null)
				return;
			if (saveTableModel(model, fileOutput, excludeColumns)) {
				WorkflowStep step;
				String includeColumns;
				if (excludeColumns.isEmpty())
					includeColumns = "";
				else {
					List<String> includeColumnList = new ArrayList<>(model.getAllNames());
					includeColumnList.removeAll(excludeColumns);
					includeColumns = ", " + includeColumnList.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", "));
				}
				String path = qupath.getProject() == null ? fileOutput.toURI().getPath() : fileOutput.getParentFile().toURI().getPath();
				if (type == TMACoreObject.class) {
					step = new DefaultScriptableWorkflowStep("Save TMA measurements",
							String.format("saveTMAMeasurements('%s'%s)", path, includeColumns)
							);
				}
				else if (type == PathAnnotationObject.class) {
					step = new DefaultScriptableWorkflowStep("Save annotation measurements",
							String.format("saveAnnotationMeasurements('%s\'%s)", path, includeColumns)
							);
				} else if (type == PathDetectionObject.class) {
					step = new DefaultScriptableWorkflowStep("Save detection measurements",
							String.format("saveDetectionMeasurements('%s'%s)", path, includeColumns)
							);
				} else {
					step = new DefaultScriptableWorkflowStep("Save measurements",
							String.format("saveMeasurements('%s', %s%s)", path, type == null ? null : type.getName(), includeColumns)
							);
				}
				imageData.getHistoryWorkflow().addStep(step);
			}
		});
		buttons.add(btnSave);


		Stage frame = new Stage();
		frame.initOwner(qupath.getStage());
		frame.titleProperty().bind(title);


		BorderPane paneTable = new BorderPane();
		paneTable.setCenter(table);
		// Add text field to filter visible columns
		TextField tfColumnFilter = new TextField();
		GridPane paneFilter = new GridPane();
		paneFilter.add(new Label("Column filter"), 0, 0);
		paneFilter.add(tfColumnFilter, 1, 0);
		GridPane.setHgrow(tfColumnFilter, Priority.ALWAYS);
		paneFilter.setHgap(5);
		if (tmaCoreList) {
			CheckBox cbHideMissing = new CheckBox("Hide missing cores");
			paneFilter.add(cbHideMissing, 2, 0);
			cbHideMissing.selectedProperty().addListener((v, o, n) -> {
				if (n) {
					model.setPredicate(p -> (!(p instanceof TMACoreObject)) || !((TMACoreObject)p).isMissing());
				} else
					model.setPredicate(null);
			});
			cbHideMissing.setSelected(true);
		}
		
		paneFilter.setPadding(new Insets(2, 5, 2, 5));
		paneTable.setBottom(paneFilter);
		StringProperty columnFilter = tfColumnFilter.textProperty();
		columnFilter.addListener((v, o, n) -> {
			String val = n.toLowerCase().trim();
			if (val.isEmpty()) {
				for (TableColumn<?, ?> col : table.getColumns()) {
					if (!col.isVisible())
						col.setVisible(true);
				}
				return;
			}
			for (TableColumn<?, ?> col : table.getColumns()) {
				if (col == colThumbnails) // Retain thumbnails
					continue;
				var name = col.getText().toLowerCase();
				col.setVisible(name.contains(val));
			}
		});
		
		// Add some extra options
		var popup = new ContextMenu();
		var miShowImages = new CheckMenuItem("Show images");
		miShowImages.selectedProperty().bindBidirectional(showThumbnailsProperty);
		popup.getItems().setAll(
				miShowImages
				);
		var btnExtra = GuiTools.createMoreButton(popup, Side.RIGHT);


		BorderPane pane = new BorderPane();
		//		pane.setCenter(table);
		splitPane.getItems().add(paneTable);
		pane.setCenter(splitPane);
		var paneButtons = PaneTools.createColumnGridControls(buttons.toArray(new ButtonBase[0]));
		var paneButtons2 = new BorderPane(paneButtons);
		paneButtons2.setRight(btnExtra);
		pane.setBottom(paneButtons2);

		
		PathObjectHierarchyListener listener = new PathObjectHierarchyListener() {

			@Override
			public void hierarchyChanged(PathObjectHierarchyEvent event) {
				if (event.isChanging())
					return;
				
				if (!Platform.isFxApplicationThread()) {
					Platform.runLater(() -> hierarchyChanged(event));
					return;
				}
				if (imageData != null)
					displayedName.set(ServerTools.getDisplayableImageName(imageData.getServer()));
				if (event.isAddedOrRemovedEvent())
					model.setImageData(imageData, imageData.getHierarchy().getObjects(null, type));
				else
					model.refreshEntries();
				table.refresh();
				if (histogramDisplay != null)
					histogramDisplay.refreshHistogram();
			}
			
		};
		
		
		TableViewerListener tableViewerListener = new TableViewerListener(viewer, table);

		frame.setOnShowing(e -> {
			hierarchy.addPathObjectListener(listener);
			viewer.addViewerListener(tableViewerListener);
		});
		frame.setOnHiding(e -> {
			hierarchy.removePathObjectListener(listener);
			viewer.removeViewerListener(tableViewerListener);
		});

		Scene scene = new Scene(pane, 600, 500);
		frame.setScene(scene);
		frame.show();
		
		
		// Add ability to remove entries from table
		ContextMenu menu = new ContextMenu();
		Menu menuLimitClasses = new Menu("Show classes");
		menu.setOnShowing(e -> {
			Set<PathClass> representedClasses = model.getBackingListEntries().stream().map(p -> p.getPathClass() == null ? null : p.getPathClass().getBaseClass()).collect(Collectors.toCollection(() -> new HashSet<>()));
			representedClasses.remove(null);
			if (representedClasses.isEmpty()) {
				menuLimitClasses.setVisible(false);
			}
			else {
				menuLimitClasses.setVisible(true);
			}
			menuLimitClasses.getItems().clear();
			List<PathClass> sortedClasses = new ArrayList<>(representedClasses);
			Collections.sort(sortedClasses);
			MenuItem miClass = new MenuItem("All");
			miClass.setOnAction(e2 -> {
				model.setPredicate(null);
				histogramDisplay.refreshHistogram();
			});
			menuLimitClasses.getItems().add(miClass);
			for (PathClass pathClass : sortedClasses) {
				miClass = new MenuItem(pathClass.getName());
				miClass.setOnAction(e2 -> {
					model.setPredicate(p -> pathClass.isAncestorOf(p.getPathClass()));
					histogramDisplay.refreshHistogram();
				});
				menuLimitClasses.getItems().add(miClass);
			}
		});
		
		if (type != TMACoreObject.class) {
			menu.getItems().add(menuLimitClasses);
			table.setContextMenu(menu);
		}

	}

	
	private void maybeCenterROI(PathObject pathObject) {
		if (pathObject == null)
			return;
		var roi = pathObject.getROI();
		var viewer = qupath.getViewer();
		if (roi != null && viewer != null && viewer.getHierarchy() != null)
			viewer.centerROI(roi);
	}



	class TMACoreTableCell extends TableCell<PathObject, PathObject> {
		
		private QuPathViewer viewer;
		private boolean paintObject;
		
		private Image image;
		
		private ImageServer<BufferedImage> server;
		private Canvas canvas = new Canvas();
		private double preferredSize = 100;
		
		private Future<?> task;

		TMACoreTableCell(ImageServer<BufferedImage> server, double padding, QuPathViewer viewer, boolean paintObject) {
			logger.trace("Creating new cell ({})", +System.identityHashCode(this));
			this.server = server;
			this.viewer = viewer;
			this.paintObject = paintObject;
			this.setContentDisplay(ContentDisplay.CENTER);
			this.setAlignment(Pos.CENTER);
			canvas.setWidth(preferredSize);
			canvas.setHeight(preferredSize);
			canvas.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 4, 0, 1, 1);");
			canvas.heightProperty().bind(canvas.widthProperty());
			
			tableColumnProperty().addListener((v, o, n) -> {
				canvas.widthProperty().unbind();
				if (n != null)
					canvas.widthProperty().bind(n.widthProperty().subtract(padding*2));
			});
			
			if (paintObject && viewer != null) {
				var options = viewer.getOverlayOptions();
				options.lastChangeTimestamp().addListener((v, o, n) -> updateImage(getItem(), false));
				viewer.getImageDisplay().changeTimestampProperty().addListener((v, o, n) -> updateImage(getItem(), false));
			}
			// Update image on width changes
			// Required to handle the fact that object ROIs may need to be repainted
			// with updated stroke widths
			canvas.widthProperty().addListener((v, o, n) -> updateImage(getItem(), false));
		}


		@Override
		protected void updateItem(PathObject pathObject, boolean empty) {
			super.updateItem(pathObject, empty);
			setText(null);
			if (empty || pathObject == null || pathObject.getROI() == null) {
				setGraphic(null);
				return;
			}
			setGraphic(canvas);
			updateImage(pathObject, false);
		}
		
		private void updateImage(PathObject pathObject, boolean useCache) {
			var roi = pathObject == null ? null : pathObject.getROI();
			if (roi == null) {
				return;
			}
			try {
				if (useCache && image != null && image.getWidth() == canvas.getWidth() && image.getHeight() == canvas.getHeight()) {
					GuiTools.paintImage(canvas, image);
					return;
				}
				// Don't draw on a tiny canvas
				if (canvas.getWidth() <= 2 || canvas.getHeight() <= 2) {
					clearCanvas();
					return;
				}
				if (task != null)
					task.cancel(true);
				
//				new Thread(new PathObjectThumbnailTask(pathObject)).run();
				task = ForkJoinPool.commonPool().submit(new PathObjectThumbnailTask(pathObject));
			} catch (Exception e) {
				logger.error("Problem reading thumbnail for {}: {}", pathObject, e);
			}
		}
		
		
		private void clearCanvas() {
			canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
		}
		
		
		
		class PathObjectThumbnailTask implements Runnable {
			
			private PathObject pathObject;
			
			PathObjectThumbnailTask(PathObject pathObject) {
				super();
				this.pathObject = pathObject;
			}

			@Override
			public void run() {
				
				if (Thread.interrupted() || pathObject == null || !pathObject.hasROI())
					return;
				
				var roi = pathObject.getROI();
				double scaleFactor = 1.2; // Used to give a bit more context around the ROI
				double downsample = Math.max(roi.getBoundsWidth() * scaleFactor / canvas.getWidth(), roi.getBoundsHeight() * scaleFactor / canvas.getHeight());
				if (downsample < 1)
					downsample = 1;
				
//				double downsample = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) / maxDim;
				Image imageNew = null;
				try {
					BufferedImage img;
					if (viewer != null) {
						img = new BufferedImage((int)canvas.getWidth(), (int)canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
						var g2d = img.createGraphics();
						g2d.setClip(0, 0, img.getWidth(), img.getHeight());
						g2d.scale(1.0/downsample, 1.0/downsample);
						
						double x = roi.getCentroidX() - img.getWidth() / 2.0 * downsample;
						double y = roi.getCentroidY() - img.getHeight() / 2.0 * downsample;
						g2d.translate(-x, -y);
						
						var renderer = viewer.getServer() == server ? viewer.getImageDisplay() : null;
						viewer.getImageRegionStore().paintRegionCompletely(server, g2d, g2d.getClipBounds(), roi.getZ(), roi.getT(), downsample, null, renderer, 500L);
						if (paintObject) {
							var options = viewer.getOverlayOptions();
							PathHierarchyPaintingHelper.paintObject(pathObject, false, g2d, null, options, null, downsample);
						}
						g2d.dispose();
					} else {
						RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, roi);
						img = server.readBufferedImage(request);
					}

					imageNew = SwingFXUtils.toFXImage(img, null);
				} catch (Exception e) {
					logger.error("Unable to draw image for " + pathObject, e);
				}

				if (imageNew != null) {
					var imageTemp = imageNew;
					Platform.runLater(() -> {
						image = imageTemp;
						GuiTools.paintImage(canvas, imageTemp);
					});
				} else {
					clearCanvas();
				}
			}

			
			
		}

	}
	



	static class BasicTableCell<S, T> extends TableCell<S, T> {

		public BasicTableCell() {
			setAlignment(Pos.CENTER);
		}

		@Override
		protected void updateItem(T item, boolean empty) {
			super.updateItem(item, empty);
			if (item == null || empty) {
				setText(null);
				setGraphic(null);
				return;
			}
			setText(item.toString());
		}

	}



	static class NumericTableCell<T> extends TableCell<T, Number> {

		private HistogramDisplay histogramDisplay;

		public NumericTableCell(final HistogramDisplay histogramDisplay) {
			this.histogramDisplay = histogramDisplay;
		}


		@Override
		protected void updateItem(Number item, boolean empty) {
			super.updateItem(item, empty);
			if (item == null || empty) {
				setText(null);
				setStyle("");
			} else {
				setAlignment(Pos.CENTER);
				if (Double.isNaN(item.doubleValue()))
					setText("-");
				else {
					if (item.doubleValue() >= 1000)
						setText(GeneralTools.formatNumber(item.doubleValue(), 1));
					else if (item.doubleValue() >= 10)
						setText(GeneralTools.formatNumber(item.doubleValue(), 2));
					else
						setText(GeneralTools.formatNumber(item.doubleValue(), 3));
				}


				setOnMouseClicked(e -> {
					if (e.isAltDown() && histogramDisplay != null) {
						histogramDisplay.showHistogram(getTableColumn().getText());
						e.consume();
						//	            		showChart(column);
					}
				});

				//			                setTooltip(new Tooltip(df6.format(item))); // Performance issue?
			}
		}

	}






	class TableViewerListener implements QuPathViewerListener {

		private TableView<PathObject> table;
		private QuPathViewer viewer;

		TableViewerListener(final QuPathViewer viewer, final TableView<PathObject> table) {
			this.viewer = viewer;
			this.table = table;
		}

		@Override
		public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {
			// Stop listening to the viewer when the data changes
			if (this.viewer == viewer && imageDataNew != imageDataOld)
				viewer.removeViewerListener(this);
		}

		@Override
		public void visibleRegionChanged(QuPathViewer viewer, Shape shape) {}

		@Override
		public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {
//			if (this.viewer != null || table.getSelectionModel().getSelectedItem() == pathObjectSelected || !table.getItems().contains(pathObjectSelected))
//				return;
			
			if (!Platform.isFxApplicationThread()) {
				Platform.runLater(() -> selectedObjectChanged(viewer, pathObjectSelected));
				return;
			}
			synchronizeTableToSelectionModel(viewer.getHierarchy(), table);

//			Platform.runLater(() -> {
//				if (pathObjectSelected == null)
//					table.getSelectionModel().clearSelection();
//				else {
//					table.getSelectionModel().select(pathObjectSelected);
//					// Scroll to the object if it is present in the table
//					//					if (table.getSelectionModel().getSelectedItem() == pathObjectSelected)
//					table.scrollTo(pathObjectSelected);
//				}
//			});
		}

		@Override
		public void viewerClosed(QuPathViewer viewer) {
			viewer.removeViewerListener(this);
			this.viewer = null; // Remove reference
		}

	}



	//	public static String getTableString(final JTable table, final String delim) {
	//		return getTableModelString(table.getModel(), delim);
	//	}

	/**
	 * Get a list of Strings representing table data.
	 * <p>
	 * Each entry in the list corresponds to a row.
	 * 
	 * @param <T> the data type for the table
	 * @param model
	 * @param delim
	 * @param excludeColumns
	 * @return
	 */
	public static <T> List<String> getTableModelStrings(final PathTableData<T> model, final String delim, Collection<String> excludeColumns) {
		List<String> rows = new ArrayList<>();
		
		StringBuilder sb = new StringBuilder();
		
		List<String> names = new ArrayList<>(model.getAllNames());
		names.removeAll(excludeColumns);
		
		int nColumns = names.size();
		for (int col = 0; col < nColumns; col++) {
			if (names.get(col).chars().filter(e -> e == '"').count() % 2 != 0)
				logger.warn("Syntax is ambiguous (i.e. misuse of '\"'), which might result in inconsistencies/errors.");
			if (names.get(col).contains(delim))
				sb.append("\"" + names.get(col) + "\"");
			else
				sb.append(names.get(col));
			
			if (col < nColumns - 1)
				sb.append(delim);
		}
		rows.add(sb.toString());
		sb.setLength(0);
		
		for (T object : model.getItems()) {
			for (int col = 0; col < nColumns; col++) {
				String val = model.getStringValue(object, names.get(col));
				if (val != null) {
					if (val.contains("\""))
						logger.warn("Syntax is ambiguous (i.e. misuse of '\"'), which might result in inconsistencies/errors.");
					if (val.contains(delim))
						sb.append("\"" + val + "\"");
					else
						sb.append(val);						
				}
//				double value = model.getNumericValue(object, model.getAllNames().get(col));
//				if (Double.isNaN(value))
//					sb.append("-");
//				else
//					sb.append(GeneralTools.getFormatter(4).format(value));
				if (col < nColumns - 1)
					sb.append(delim);
			}
			rows.add(sb.toString());
			sb.setLength(0);
		}
		return rows;
	}

	/**
	 * Get a single String representing the data in a table.
	 * <p>
	 * Note: if the required String is too long (approximately Integer.MAX_VALUE characters), this will throw an IllegalArgumentException.
	 * 
	 * @param <T> the data type for the items in the table
	 * @param model
	 * @param delim
	 * @param excludeColumns
	 * @return
	 * @throws IllegalArgumentException 
	 */
	public static <T> String getTableModelString(final PathTableData<T> model, final String delim, Collection<String> excludeColumns) throws IllegalArgumentException {
		List<String> rows = getTableModelStrings(model, delim, excludeColumns);
		long length = rows.stream().mapToLong(r -> r.length()).sum() + rows.size() * System.lineSeparator().length();
		long maxLength = Integer.MAX_VALUE - 1;
		if (length > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Requested string is too long! Requires " + maxLength + " characters, but Java arrays limited to " + maxLength);
		logger.debug("Getting table string (approx {} characters, {} % of maximum)", length, Math.round(length / (double)maxLength  * 100));
		return String.join(System.lineSeparator(), rows);
	}

	/**
	 * Get a single String representing the data in a table and copy it to the clipboard.
	 * <p>
	 * Note: this may not be possible if the String is too long, see {@link #getTableModelString(PathTableData, String, Collection)}.
	 * 
	 * @param model
	 * @param excludeColumns
	 */
	public static void copyTableContentsToClipboard(final PathTableData<?> model, Collection<String> excludeColumns) {
		if (model == null) {
			logger.warn("No table available to copy!");
			return;
		}
		String string = getTableModelString(model, PathPrefs.tableDelimiterProperty().get(), excludeColumns);
		Clipboard clipboard = Clipboard.getSystemClipboard();
		ClipboardContent content = new ClipboardContent();
		content.putString(string);
		clipboard.setContent(content);
	}
	
	
	private static File promptForOutputFile() {
		String ext = ",".equals(PathPrefs.tableDelimiterProperty().get()) ? "csv" : "txt";
		return Dialogs.promptToSaveFile(null, null, null, "Results data", ext);
	}
	
	/**
	 * Save the data from a table to a text file, using the default delimiter from {@link PathPrefs}.
	 * @param tableModel the data to export
	 * @param fileOutput the file to write the text to; if null, a file chooser will be shown
	 * @param excludeColumns headings for columns that should be excluded
	 * @return
	 */
	public static boolean saveTableModel(final PathTableData<?> tableModel, File fileOutput, Collection<String> excludeColumns) {
		if (fileOutput == null) {
			fileOutput = promptForOutputFile();
			if (fileOutput == null)
				return false;
		}
		try (PrintWriter writer = new PrintWriter(fileOutput, StandardCharsets.UTF_8)) {
			for (String row : getTableModelStrings(tableModel, PathPrefs.tableDelimiterProperty().get(), excludeColumns))
				writer.println(row);
			writer.close();
			return true;
		} catch (IOException e) {
			logger.error("Error writing file to " + fileOutput, e);
		}
		return false;
	}
	
	
	private boolean synchronizingTableToModel = false;
	private boolean synchronizingModelToTable = false;
	
	private void synchronizeSelectionModelToTable(final PathObjectHierarchy hierarchy, final ListChangeListener.Change<? extends PathObject> change, final TableView<PathObject> table) {
		if (synchronizingTableToModel || hierarchy == null)
			return;
		
		PathObjectSelectionModel model = hierarchy.getSelectionModel();
		if (model == null) {
			return;
		}
		
		boolean wasSynchronizingToTree = synchronizingModelToTable;
		try {
			synchronizingModelToTable = true;
			
			// Check - was anything removed?
			boolean removed = false;
			if (change != null) {
				while (change.next())
					removed = removed | change.wasRemoved();
			}
			
			MultipleSelectionModel<PathObject> treeModel = table.getSelectionModel();
			List<PathObject> selectedItems = treeModel.getSelectedItems();
			
			// If we just have no selected items, and something was removed, then clear the selection
			if (selectedItems.isEmpty() && removed) {
				model.clearSelection();
				return;				
			}
			
			// If we just have one selected item, and also items were removed from the selection, then only select the one item we have
//			if (selectedItems.size() == 1 && removed) {
			if (selectedItems.size() == 1) {
				model.setSelectedObject(selectedItems.get(0), false);
				return;
			}
			
			// If we have multiple selected items, we need to ensure that everything in the tree matches with everything in the selection model
			Set<PathObject> toSelect = new HashSet<>(treeModel.getSelectedItems());
			PathObject primary = treeModel.getSelectedItem();
			model.setSelectedObjects(toSelect, primary);
		} finally {
			synchronizingModelToTable = wasSynchronizingToTree;
		}
	}
	
	
	private void synchronizeTableToSelectionModel(final PathObjectHierarchy hierarchy, final TableView<PathObject> table) {
		if (synchronizingModelToTable || hierarchy == null)
			return;
		boolean ownsChanges = !synchronizingTableToModel;
		try {
			synchronizingTableToModel = true;
			
			PathObjectSelectionModel model = hierarchy.getSelectionModel();
			TableViewSelectionModel<PathObject> tableModel = table.getSelectionModel();
			if (model == null || model.noSelection()) {
				tableModel.clearSelection();
				return;
			}
			
			if (model.singleSelection() || tableModel.getSelectionMode() == SelectionMode.SINGLE) {
				int ind = table.getItems().indexOf(model.getSelectedObject());
				if (ind >= 0) {
					tableModel.clearAndSelect(ind);
					table.scrollTo(ind);
				}
				else
					tableModel.clearSelection();
				return;
			}
			
			// Loop through all possible selections, and select them if they should be selected (and not if they shouldn't)
			// For performance reasons, we need to do this using arrays - otherwise way too many events may be fired
			int n = table.getItems().size();
			PathObject mainSelectedObject = model.getSelectedObject();
			int mainObjectInd = -1;
			int[] indsToSelect = new int[table.getItems().size()];
			int count = 0;
			for (int i = 0; i < n; i++) {
				PathObject temp = table.getItems().get(i);
				if (temp == mainSelectedObject)
					mainObjectInd = i;
				if (model.isSelected(temp)) {
					indsToSelect[count] = i;
					count++;
				}
			}
			tableModel.clearSelection();
			if (count > 0) {
				int maxCount = 1000;
				if (count > maxCount) {
					logger.warn("Only the first {} items will be selected in the table (out of {} total) - otherwise QuPath can grind to a halt, sorry",
							maxCount, count);
					count = maxCount;
				}
				tableModel.selectIndices(indsToSelect[0], Arrays.copyOfRange(indsToSelect, 1, count));
			}
			
//			for (int i = 0; i < n; i++) {
//				PathObject temp = table.getItems().get(i);
//				if (temp == mainSelectedObject)
//					mainObjectInd = i;
//				if (model.isSelected(temp)) {
//					// Only select if necessary, or if this is the main selected object
//					if (!tableModel.isSelected(i))
//						tableModel.select(i);
//				}
//				else
//					tableModel.clearSelection(i);
//			}
			// Ensure that the main object is focussed & its node expanded
			if (mainObjectInd >= 0 && model.singleSelection()) {
				tableModel.select(mainObjectInd);
				table.scrollTo(mainObjectInd);
			}
			
		} finally {
			if (ownsChanges)
				synchronizingTableToModel = false;
		}
	}
	

}
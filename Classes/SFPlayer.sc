SFPlayer {
	var <path, <outbus, <server, <>autoSetSampleRate, <>autoSetOutputChannels;
	var <bufnum, <sf, cond, curNode, curTime, <curSynth, <synthName;
	var <window, bounds, outMenu, playButton, ampSlider, ampNumber, targetText, addActionMenu;
	var <amp, <isPlaying = false, wasPlaying, hasGUI, <startTime, timeString, <sfView, <cuesView, <gridView, <timeGrid, guiRoutine;
	var scope, iEnv, clock;
	var <cues, offset, cueMenu, lastStart, cueOffsetNum, <skin;
	var <openFilePending = false, <openGUIafterLoading = false, tempBounds, tempAction, <>duplicateSingleChannel = true;
	var <ampSpec;
	var rateVar, addActionVar, targetVar, bufsizeVar;
	var <>switchTargetWhilePlaying = true;

	*new {arg path, outbus, server, skin, autoSetSampleRate = true, autoSetOutputChannels = true; /*autoSetSampleRate and autoSetOutputChannels are only exectuded it the server is not booted*/
		^super.newCopyArgs(path, outbus, server, autoSetSampleRate, autoSetOutputChannels).initSFPlayer(skin);
	}

	initSFPlayer {arg argSkin;
		skin = argSkin ?? {SFPlayerSkin.default};
		server = server ?? Server.default;
		this.rate_(1);
		// rateVar = 1;
		this.addAction_(0);
		// addActionVar = 0;
		// targetVar = 1;
		this.target_(1);
		outbus ?? {this.outbus_(0)};
		bufsizeVar = 65536 * 8;
		// server.serverRunning.not({server.boot}); //this was not working (missing .if); we have waitForBoot in runSetup anyway
		offset = 0;
		path.isNil.if({
			openFilePending = true;
			// Dialog.getPaths({arg paths;
			Dialog.openPanel({arg paths;
				// path = paths[0];
				path = paths;
				openFilePending = false;
				this.runSetup;
			})
		}, {
			openFilePending = false;
			this.runSetup;
		})
	}

	getAddActionsArray {
		^Node.addActions.select({|key, val| val.asString.size > 1}).getPairs.clump(2).sort({|a, b| a[1] < b[1]}).flop[0]
	}

	getAddActionIndex {arg addActionArg; //from name or number; this is probably not foolproof...
		if(addActionArg.isKindOf(SimpleNumber), {
			^addActionArg;
		}, {
			//assume name
			^Node.addActions.select({|key, val| val.postln.asString.containsi(addActionArg.asString)}).asArray[0];
		})
	}

	runSetup {
		sf = SoundFile.new;
		{sf.openRead(path)}.try({"Soundfile could not be opened".warn});
		cond = Condition.new;
		if(server.options.numOutputBusChannels < sf.numChannels, {
			if(server.serverRunning.not && autoSetOutputChannels, { //if server is not running, set the number of output channels
				format("%: setting server's options.numOutputBusChannels to %", this.class.name, sf.numChannels).postln;
				server.options.numOutputBusChannels_(sf.numChannels);

			}, {
				format("%: server's options.numOutputBusChannels (%) is lower than soundfile's numChannels (%)", this.class.name, server.options.numOutputBusChannels, sf.numChannels).warn;
			});
		});
		if(server.serverRunning.not && server.options.sampleRate.isNil && autoSetSampleRate, { //also set the samplerate if server is not running
			//and sampleRate too rate too
			format("%: setting server's options.sampleRate to %", this.class.name, sf.sampleRate).postln;
			server.options.sampleRate_(sf.sampleRate);
		});
		server.waitForBoot({
			this.buildSD;
		});
		sf.close;
		amp = 1;
		isPlaying = false;
		wasPlaying = false;
		hasGUI = false;
		startTime = 0.0;
		if(openGUIafterLoading, {
			this.gui(tempBounds, tempAction);
			openGUIafterLoading = false;
		});
	}

	buildSD {
		synthName = "SFPlayer"++sf.numChannels;
		SynthDef(synthName, {arg gate = 1, buffer, amp = 1, rate = 1, outbus;
			var diskin;
			diskin = VDiskIn.ar(sf.numChannels, buffer, (BufSampleRate.kr(buffer) / SampleRate.ir) * rate);
			((sf.numChannels == 1) && duplicateSingleChannel).if({
				diskin = diskin.dup
			});
			Out.ar(outbus, diskin *
				EnvGen.kr(Env([0, 1, 0], [0.02, 0.02], \sin, 1), gate, doneAction: 2) *
				Lag.kr(amp, 0.1))
		}).add;
	}

	loadBuffer {arg bufsize = 65536, startTime = 0;
		bufsize = bufsize * sf.numChannels;
		server.sendMsg(\b_alloc, bufnum = server.bufferAllocator.alloc, bufsize, sf.numChannels,
			[\b_read, bufnum, path, startTime * sf.sampleRate, bufsize, 0, 1]);
	}

	bufsize {^bufsizeVar}
	bufsize_ {arg val; bufsizeVar = val}

	addAction  {^addActionVar}
	addAction_ {arg val;
		// val = Node.addActions[val];
		val !? {
			addActionVar = val;
			this.changed(\addAction, this.addAction);
		};
	}

	target {^targetVar}
	target_ {arg val;
		targetVar = val;
		this.changed(\target, val, switchTargetWhilePlaying);
		if(isPlaying && switchTargetWhilePlaying, {
			Node.actionNumberFor(this.addAction).switch(
				0, {curSynth.moveToHead(this.target.asTarget)}, //head
				1, {curSynth.moveToTail(this.target.asTarget)}, //tail
				2, {curSynth.moveBefore(this.target.asTarget)}, //before
				3, {curSynth.moveAfter(this.target.asTarget)}, //after
				4, {format("%: can't replace % while playing", this.class.name, this.target.asTarget).warn}, //replace
			)
		});
	}

	rate {^rateVar}
	rate_ {arg val;
		rateVar = val;
		curSynth.set(\rate, rateVar);
		this.changed(\rate, this.rate);
	}

	play {arg bufsize, addAction, target, rate;
		bufsize !? {bufsizeVar = bufsize};
		addAction !? {addActionVar = addAction};
		target !? {targetVar = target};
		rate !? {rateVar = rate};
		(isPlaying.not and: {startTime < sf.duration} and: synthName.notNil).if({
			Routine.run({
				clock = TempoClock.new;
				lastStart = startTime;
				clock.sched(sf.duration - startTime + 0.1, {this.stop});
				this.loadBuffer(bufsizeVar, startTime);
				server.sync(cond);
				// server.sendMsg(\s_new, "SFPlayer"++sf.numChannels,
				// curNode = server.nodeAllocator.alloc(1), addAction, target,
				// \buffer, bufnum, \amp, amp, \outbus, outbus, \rate, rate);
				curSynth = Synth(synthName, [\buffer, bufnum, \amp, amp, \outbus, outbus, \rate, rateVar], targetVar, addActionVar);
				curNode = curSynth.nodeID;
				isPlaying = true;
				this.changed(\isPlaying, this.isPlaying);
				// hasGUI.if({
				// this.playGUIRoutine
				// })
			})
		})
	}

	pause {
		isPlaying.if({
			this.stop( false );
			this.startTime_( curTime );
		})
	}

	stop {arg updateStart = true;
		var oldbufnum;
		isPlaying.if({
			clock.stop;
			// server.sendMsg(\n_set, curNode, \gate, 0);
			curSynth.release;
			oldbufnum = bufnum;
			// this.stopGUIRoutine;
			isPlaying = false;
			this.changed(\isPlaying, this.isPlaying, updateStart);
			SystemClock.sched(0.2, {
				server.sendBundle(nil, [\b_close, oldbufnum], [\b_free, oldbufnum]);
				server.bufferAllocator.free(oldbufnum)
			});
			updateStart.if({{this.startTime_(lastStart)}.defer(0.1)});
			// hasGUI.if({
			// 	{playButton.value_(0)}.defer;
			// })
		})
	}

	outbus_ {arg newOut, updateMenu = true;
		outbus = newOut;
		isPlaying.if({
			server.sendMsg(\n_set, curNode, \outbus, outbus);
		});
		this.changed(\outbus, outbus, updateMenu);
		// (hasGUI and: {updateMenu}).if({
		// 	outMenu.value_(outbus)
		// })
	}

	amp_ {arg newAmp, source; //source: \number, \slider or none
		amp = newAmp;
		isPlaying.if({
			server.sendMsg(\n_set, curNode, \amp, amp)
		});
		this.changed(\amp, amp, source);
		// hasGUI.if({
		// 	// ampNumber.valueAction_(newAmp.ampdb);
		// 	ampNumber.value_(newAmp.ampdb);
		// 	ampSlider.value_(ampSpec.unmap(newAmp.ampdb));
		// })
	}

	startTime_ {arg newStartTime;
		startTime = (newStartTime + offset).max(0).min(sf.duration);
		this.changed(\startTime, startTime);
		// hasGUI.if({
		// 	sfView.timeCursorPosition_((startTime * sf.sampleRate).round);
		// 	timeString.string_(startTime.asTimeString[3..10]);
		// 	cueOffsetNum.value_(0);
		// 	offset = 0;
		// })
	}

	gui {arg argBounds, doneAction;
		if(openFilePending, {
			tempBounds = argBounds;
			tempAction = doneAction;
			openGUIafterLoading = true;
		}, {
			ampSpec = [-90, 12].asSpec;
			bounds = argBounds ?? {Rect(200, 200, 980, 600)};
			window = Window(path.basename, bounds);
			window.view.background_(skin.background);
			window.view.mouseDownAction_({arg view, x, y, modifiers, buttonNumber, clickCount;
				var sfBounds = sfView.bounds;
				if((x <= sfBounds.left) && (y >= sfBounds.top) && (y <= sfBounds.bottom), {
					if(this.isPlaying.not, {this.startTime_(0)});
				});
			});// set startTime to 0 when clicking to the left of the soundfileview
			timeGrid = DrawGrid(nil, ControlSpec(0, sf.duration, units: \s).grid, nil);
			timeGrid.fontColor_(skin.string);
			timeGrid.font_(Font("Arial", 10));
			timeGrid.gridColors_([skin.string, nil]);
			window.onClose_({isPlaying.if({this.stop}); hasGUI = false; this.removeDependant(this)});
			hasGUI = true;
			window.view.layout_(
				VLayout(
					HLayout(
						GridLayout.rows(
							[
								[
									timeString = StaticText(window)
									.font_(Font("Arial", 72))
									.stringColor_(skin.string)
									.string_(startTime.asTimeString[3..10])
									.fixedWidth_(300),
									rows: 3
								],

								playButton = Button.new(window)
								.states_([
									[">", skin.string, skin.background],
									["||", skin.string, skin.background]])
								.focus(true)
								.action_({arg button;
									[{this.pause}, {this.play}][button.value].value;
								})
								.minWidth_(120),

								StaticText(window)
								.string_("Outbus")
								.stringColor_(skin.string),
								outMenu = PopUpMenu(window)
								.items_(server.options.numAudioBusChannels.collect({arg i; i.asString}))
								.value_(outbus ?? {0})
								.action_({arg menu; this.outbus_(menu.value, false); playButton.focus(true)})
								.stringColor_( skin.string )
								.background_(skin.background)
								.maxWidth_(120),

								StaticText(window)
								.string_("Amplitude (in db)")
								.stringColor_( skin.string),
								nil,
								ampNumber = NumberBox(window)
								.value_(amp.ampdb)
								.action_({arg me;
									this.amp_(me.value.dbamp, \number);
									// ampSlider.value_(ampSpec.unmap(me.value);
									// playButton.focus(true));
									playButton.focus(true);
								}).maxWidth_(60),

								nil,
							], [
								nil,

								Button.new(window, Rect(310, 40, 120, 20))
								.states_([
									["[]", skin.string, skin.background]])
								.canFocus_(false)
								.action_({this.stop}),

								StaticText(window)
								.string_("addAction")
								.stringColor_(skin.string),
								addActionMenu = PopUpMenu(window)
								.items_(this.getAddActionsArray)
								.value_(this.getAddActionIndex(this.addAction))
								.action_({arg menu; this.addAction_(menu.value); playButton.focus(true)})
								.stringColor_( skin.string )
								.background_(skin.background)
								.maxWidth_(120)
								,

								[
									ampSlider = Slider(window)
									.value_(ampSpec.unmap(amp))
									.canFocus_(false)
									.orientation_(\horizontal)
									.action_({arg me;
										this.amp_(ampSpec.map(me.value).round(0.1).dbamp, \slider);
										// ampNumber.value_(ampSpec.map(me.value).round(0.1))
									}),
									columns: 3
								],
								nil
							], [
								nil,

								Button.new(window, Rect(310, 70, 120, 20))
								.states_([
									["Scope On", skin.string, skin.background],
									["Scope Off", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({arg button;
									[
										{scope.window.close},
										{scope = server.scope(sf.numChannels, outbus)}
									][button.value].value;
								}),

								StaticText(window)
								.string_("Target")
								.stringColor_(skin.string),
								targetText = TextField(window)
								.value_(this.target.asString)
								.action_({arg menu; this.target_(menu.value.interpret); playButton.focus(true)})
								.stringColor_( skin.string )
								.background_(skin.background)
								.maxWidth_(120),

								Button.new(window)
								.states_([
									["Reset", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({this.reset}),

								nil,
								nil,
								nil,
								nil,
							]

						),
						nil
					).margins_([0, 0, 0, 0]), //end of top section with time, play/stop, amp etc
					[
						VLayout(
							[
								StackLayout(
									cuesView = UserView(window).acceptsMouse_(false),
									sfView = SoundFileView.new(window)
									.canFocus_(false)
									.soundfile_(sf)
									.timeCursorColor_(skin.sfCursor)
									.readWithTask(0, sf.numFrames
										, block: 64,
										doneAction: {window.front; doneAction.value})
									.gridOn_(false)
									.timeCursorOn_(true)
									.background_(skin.sfBackground)
									.waveColors_(Array.fill(sf.numChannels, skin.sfWaveform))
									.mouseDownAction_({this.pausePlay})
									.mouseUpAction_({this.playPaused})
									.mouseMoveAction_({arg view;
										var scrollRatio = view.viewFrames/ sf.numFrames;
										var start = view.scrollPos.linlin(0, 1, 0, 1 - scrollRatio) * sf.duration;
										var end = start + (scrollRatio * sf.duration);
										var grid = timeGrid.x.grid;
										grid.spec.minval_(start);
										grid.spec.maxval_(end);
										timeGrid.horzGrid_(grid);
										gridView.refresh;
										cuesView.refresh;
									})
									.timeCursorPosition_(0 / sf.duration),
								).mode_(\stackAll),
								stretch: 10
							],
							gridView = UserView()
							.drawFunc_({|view|
								timeGrid.bounds = Rect(0, 0, view.bounds.width, view.bounds.height);
								timeGrid.draw;
							})
							.minHeight_(12)
						).margins_([0, 0, 0, 0]).spacing_(0),
						stretch: 10
					],

					//bottom secion
					/* cues */
					HLayout(
						GridLayout.rows(
							[
								StaticText(window)
								.string_("Play From Cue:")
								.stringColor_( skin.string),
								[
									cueMenu = PopUpMenu(window)
									.items_(cues.asArray)
									.stringColor_(skin.string)
									.background_(skin.background)
									.canFocus_(false)
									.allowsReselection_(true)
									.mouseUpAction_({"MouseUp".postln;})
									.mouseDownAction_({arg view;
										isPlaying.if({wasPlaying = true;this.stop});
										view.value_(0)
									})
									.action_({arg thisMenu;
										var idx;
										idx = thisMenu.value - 1;
										(idx >= 0).if({
											this.playFromCue(cues[idx][0], idx);
										}, {
											this.playFromCue(\none, -1)
										});
										wasPlaying.if({this.play; wasPlaying = false;})
									})
									.minWidth_(300),
									columns: 2
								],
								Button(window)
								.states_([
									["Load cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									this.loadCues
								}),
								Button(window)
								.states_([
									["Save cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									this.saveCues
								}),
								StaticText(window)
								.string_("Add cues ([\\key, val])")
								.stringColor_(skin.string),
								TextField(window)
								.action_({arg me;
									var vals;
									vals = me.string.interpret;
									vals.isKindOf(Array).if({
										vals = vals.flat.clump(2);
									}, {
										vals = vals.asArray.flat.clump(2)
									});
									vals[0].isKindOf(Array).if({
										vals.do({arg thisPair;
											this.addCue(thisPair[0], thisPair[1], false)
										});
										this.sortCues;
									}, {
										this.addCue(vals[0], vals[1], true)
									});
									playButton.focus(true);
								}),
								// [nil, rows: 2, stretch: 1]
							], [
								StaticText(window)
								.string_("Cue offset:")
								.stringColor_(skin.string),
								cueOffsetNum = NumberBox(window)
								.value_(0)
								.action_({arg thisBox;
									this.offset_(thisBox.value);
									playButton.focus(true)
								})
								.maxWidth_(60),
								nil,
								Button(window)
								.states_([
									["Hide cues",  skin.string, skin.background],
									["Show cues",  skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({arg button;
									(button.value == 0).if({
										this.drawCues
									}, {
										this.hideCues
									})
								}),
								nil,
								StaticText(window)
								.string_("Remove cues ([\\key])")
								.stringColor_(skin.string),
								TextField(window)
								.action_({arg me;
									var vals;
									vals = me.string.interpret;
									vals.isKindOf(Array).if({
										vals = vals.flat;
									}, {
										vals = vals.asArray
									});
									vals.do({arg thisKey;
										this.removeCue(thisKey, false)
									});
									this.sortCues;
									playButton.focus(true);
								})
							]
						).hSpacing_(12).vSpacing_(4),
						[nil, stretch: 1]
					)
				)
			);
			window.front;
			this.addDependant(this);
		});
	}

	pausePlay {
		isPlaying.if({
			wasPlaying = true;
			this.stop;
		});
	}

	playPaused {
		startTime = sfView.timeCursorPosition / sf.sampleRate;
		wasPlaying.if({
			this.play;
			wasPlaying = false;
		}, {
			timeString.string_(startTime.asTimeString[3..10]);
		})
	}

	playGUIRoutine {
		guiRoutine = Routine.run({
			var now;
			now = Main.elapsedTime;
			loop({
				curTime = (((Main.elapsedTime - now) * rateVar) + startTime);
				{
					hasGUI.if({
						sfView.timeCursorPosition_(curTime * sf.sampleRate);
						timeString.string_(curTime.round(0.01).asTimeString[3..10]);
					})
				}.defer;
				0.1.wait;
			})
		})
	}

	stopGUIRoutine {
		guiRoutine.stop;
	}

	addCue {arg key, time, sort = true, redraw = true;
		(time < sf.duration).if({
			//			(cues.notNil and: {cues[key].notNil}).if({
			//				this.removeCue(key, false, false);
			//			});
			cues = cues.add([key, time]);
		}, {
			"You tried to add a cue past the end of the soundfile".warn;
		});
		sort.if({this.sortCues});
		redraw.if({this.drawCues});
	}

	removeCue {arg key, sort = true, redraw = true;
		var idx;
		cues.do({arg thisCue, i;
			(thisCue[0] == key).if({
				idx = i;
			});
		});
		idx.notNil.if({
			cues.removeAt(idx);
		}, {
			"Cue not found".warn
		});
		sort.if({	this.sortCues;});
		redraw.if({this.drawCues});
	}

	sortCues {
		cues.sort({arg a, b; a[1] < b[1]});
	}

	loadCues {arg path;
		path.isNil.if({
			Dialog.getPaths({arg paths;
				cues = paths[0].load;
				this.drawCues;
			})
		}, {
			cues = path.load;
			this.drawCues;
		});
	}

	saveCues {arg path;
		path.isNil.if({
			Dialog.savePanel({arg thisPath;
				cues.writeArchive(thisPath);
			})
		}, {
			cues.writeArchive(path)
		})
	}

	hideCues {
		hasGUI.if({
			cuesView.drawFunc_({});
			cuesView.refresh;
		});
	}

	drawCues {
		var points, menuItems, nTabs, inc;
		cues.notNil.if({
			hasGUI.if({
				var drawPointsArr;
				this.sortCues;
				points = cues.collect({arg thisCue;
					thisCue[1];
				});
				drawPointsArr = Array.new(points.size);
				cuesView.drawFunc_({|view|
					var scaledPoints, spec;
					spec = timeGrid.x.grid.spec;
					drawPointsArr = points.collect({arg pt;
						(pt >= spec.minval) && (pt <= spec.maxval)
					});
					scaledPoints = points.collect({arg pt; spec.unmap(pt) * view.bounds.width});
					Pen.font_(Font("Helvetica", 16));
					Pen.strokeColor_(skin.cueLine);
					scaledPoints.do({arg thisPoint, i;
						if(drawPointsArr[i], {
							Pen.moveTo(thisPoint @ 0);
							Pen.lineTo(thisPoint @ (view.bounds.height));
							Pen.stroke;
						});
					});
					Pen.fillColor_(skin.cueLabel);
					scaledPoints.do({arg thisPoint, i;
						if(drawPointsArr[i], {
							Pen.stringAtPoint(cues[i][0].asString,
								(thisPoint + 3) @ (10 + ((i % 10) * 40)));
							Pen.fillStroke;
						});
					});
				});
				menuItems = cues.collect({arg thisCue;
					var key, time, nTabs;
					(thisCue[0].asString.size > 6).if({
						// nTabs = "\t\t"
						nTabs = "  "  //fix for tabs in popupmenu
					}, {
						// nTabs = "\t\t\t"
						nTabs = "      "  //fix for tabs in popupmenu
					});
					thisCue[0].asString + nTabs + thisCue[1].asTimeString;
				});
				// cueMenu.items_(["None" + "\t\t\t" + 0.asTimeString] ++ menuItems);
				cueMenu.items_(["None" + "      " + 0.asTimeString] ++ menuItems); //fix for tabs in popupmenu
				cuesView.refresh;
			})
		})
	}

	playFromCue {arg key, idx;
		var newStart, tmp;
		idx = idx ?? {
			cues.do({arg thisCue, i;
				(thisCue[0] == key).if({
					tmp = i;
				});
			});
			tmp;
		};
		idx.notNil.if({
			(idx == -1).if({
				newStart = 0.0;
			}, {
				newStart = cues[idx][1];
			})
		});
		this.startTime_(newStart);
	}

	offset_ {arg newOffset;
		var tmp;
		tmp = startTime + offset;
		offset = newOffset;
		this.startTime_(tmp);
	}

	reset {
		isPlaying.if({this.stop});
		{
			cueMenu.value_(0);
			this.startTime_(0);
			this.amp_(1);
		}.defer(0.11)
	}

	update {arg who, what ...args;
		var value = args[0];
		// args[0] is the value
		// "update fired, what: ".post;
		// what.post;
		// ": ".post; args.postln;
		{
			what.switch(
				\addAction, {if(hasGUI, {addActionMenu.value_(this.getAddActionIndex(value))})},
				\target, {if(hasGUI, {targetText.value_(value.asNodeID.asString)})},
				\amp, {
					hasGUI.if({
						if(args[1] != \number, {
							ampNumber.value_(value.ampdb.round(0.1));
						}, {"not updating number".postln;});
						if(args[1] != \slider, {
							ampSlider.value_(ampSpec.unmap(value.ampdb));
						});
					})
				},
				\outbus, {
					var updMenu = args[1];
					(hasGUI && updMenu).if({
						outMenu.value_(value)
					})
				},
				\isPlaying, {
					if(hasGUI, {
						if(value, {
							this.playGUIRoutine;
						}, {
							this.stopGUIRoutine;
							playButton.value_(0);
						})
					})
				},
				\startTime, {
					hasGUI.if({
						sfView.timeCursorPosition_((startTime * sf.sampleRate).round);
						timeString.string_(startTime.asTimeString[3..10]);
						cueOffsetNum.value_(0);
						offset = 0;
					})
				}
			)
		}.defer;
	}

}

SFPlayerSkin {
	classvar <>default;
	var <>string, <>background, <>sfBackground, <>sfWaveform, <>sfCursor,
	<>cueLabel, <>cueLine;

	*new {arg string, background, sfBackground, sfWaveform, sfCursor, cueLabel, cueLine;
		^super.newCopyArgs(string, background, sfBackground, sfWaveform,
			sfCursor, cueLabel, cueLine).initSFPlayerSkin;
	}

	initSFPlayerSkin {
		string = string ?? {Color.grey(0.8)};
		background = background ?? {Color.grey(0.4)};
		sfBackground = sfBackground ?? {Color.grey(0.8)};
		sfWaveform = sfWaveform ?? {Color(0.4, 0.4, 0.7)};
		sfCursor = sfCursor ?? {Color.blue};
		cueLabel = cueLabel ?? {Color.black};
		cueLine = cueLine ?? {Color.grey(0.2)}
	}

	*initClass {
		default = this.new;
	}
}

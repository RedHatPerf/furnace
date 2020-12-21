import React, { useState, useEffect, useRef } from 'react'
import '@patternfly/patternfly/patternfly.css';
import {
  Button,
  Checkbox,
  Page,
  PageHeader,
  PageSection,
  Progress,
  ProgressMeasureLocation,
  Select,
  SelectOption,
  Spinner,
} from "@patternfly/react-core";

import './App.css';

function fetchRegistered() {
  return fetch("/proxy/registered").then(res => res.json(), error => console.error(error))
}

function startRecording(namespace, pod) {
  return fetch("/proxy/start?namespace=" + namespace + "&pod=" + pod, { method: "POST" })
    .catch(error => console.error(error))
}

function stopRecording(namespace, pod, colors, inverted) {
  return fetch("/proxy/stop?namespace=" + namespace + "&pod=" + pod + "&width=" + (window.innerWidth - 20) + "&colors=" + colors + "&inverted=" + inverted, { method: "POST" })
    .catch(error => console.error(error))
}

function fetchStatus(namespace, pod) {
  return fetch("/proxy/status?namespace=" + namespace + "&pod=" + pod)
    .then(res => res.text(), error => console.error(error));
}

function statusValue(status) {
  switch (status) {
    case "perf record": return 0;
    case "perf script": return 1;
    case "stackcollapse": return 2;
    case "flamegraph": return 3;
    case "idle": return 4;
    default: return -1;
  }
}

function App() {
  const [registered, setRegistered] = useState()
  useEffect(() => fetchRegistered().then(setRegistered), [])
  useEffect(() => {
    const timer = setInterval(() => fetchRegistered().then(setRegistered), 10000);
    return () => clearInterval(timer);
  }, []);
  const [nsOpen, setNsOpen] = useState(false)
  const [namespace, setNamespace] = useState()
  const [podOpen, setPodOpen] = useState(false)
  const [pod, setPod] = useState()
  const [colorsOpen, setColorsOpen] = useState()
  const [colors, setColors] = useState("hot")
  const [inverted, setInverted] = useState(true)
  const [symfs, setSymfs] = useState(false)
  const [recording, setRecording] = useState(false)
  const [busy, setBusy] = useState(false)
  const statusTimer = useRef()
  const [status, setStatus] = useState("idle")
  const [chartReadyTime, setChartReadyTime] = useState()
  const updateStatus = () => {
    fetchStatus(namespace, pod).then(status => {
      setStatus(status)
      if (status === "idle") {
        clearInterval(statusTimer.current);
        statusTimer.current = undefined
        setBusy(false)
        setChartReadyTime(new Date().getTime())
      }
    })
  }
  return (
    <div className="App">
      <Page
        header={(<PageHeader logo="Furnace" />)}
      >
        <PageSection style={{ "display" : "flex"}}>
          <Select
            isDisabled={recording || busy}
            placeholderText="Select namespace..."
            isOpen={nsOpen}
            onToggle={setNsOpen}
            onSelect={(_, ns) => {
              setNamespace(ns)
              setPod(undefined)
              setNsOpen(false)
              setChartReadyTime(undefined)
            }}
            selections={namespace}
            menuAppendTo="parent"
          >
            { registered && registered.map(r => r.namespace)
                .sort().filter((el,i,a) => (i===a.indexOf(el)))
                .map((ns, i) => (<SelectOption key={i} value={ns} /> )) }
          </Select>
          <Select
            isDisabled={recording || busy}
            placeholderText="Select pod..."
            isOpen={podOpen}
            onToggle={setPodOpen}
            onSelect={(_, p) => {
              setPod(p)
              setPodOpen(false)
              setChartReadyTime(undefined)
            }}
            selections={pod}
            menuAppendTo="parent"
          >
            { registered && registered.filter(r => r.namespace === namespace)
                .map(r => r.podName).sort()
                .map((p, i) => (<SelectOption key={i} value={p} /> )) }
          </Select>
          <Button
            isDisabled={ !namespace || !pod || busy }
            onClick={ () => {
              setBusy(true)
              if (recording) {
                setChartReadyTime(undefined)
                stopRecording(namespace, pod, colors, inverted)
                  .then(_ => {
                    setRecording(false)
                    statusTimer.current = setInterval(updateStatus, 2000)
                  }, _ => setBusy(false))
              } else {
                setStatus("perf record")
                startRecording(namespace, pod)
                  .then(_ => setRecording(true)).finally(() => setBusy(false))
              }
            }}
          >{ recording ? "Stop recording" : "Start recording" }</Button>
          <Checkbox
            label="Use&nbsp;--symfs"
            isChecked={symfs}
            onChange={setSymfs}
          />
          <Select
            isOpen={colorsOpen}
            onToggle={setColorsOpen}
            onSelect={(_, c) => {
              setColors(c)
              setColorsOpen(false)
            }}
            selections={colors}
            menuAppendTo="parent"
          >
            <SelectOption key={0} value="hot"/>
            <SelectOption key={1} value="chain"/>
            <SelectOption key={2} value="java"/>
            <SelectOption key={3} value="js"/>
            <SelectOption key={4} value="perl"/>
            <SelectOption key={5} value="red"/>
            <SelectOption key={6} value="green"/>
            <SelectOption key={7} value="blue"/>
            <SelectOption key={8} value="aqua"/>
            <SelectOption key={9} value="yellow"/>
            <SelectOption key={10} value="purple"/>
            <SelectOption key={11} value="orange"/>
          </Select>
          <Checkbox
            label="Inverted"
            isChecked={inverted}
            onChange={setInverted}
          />
          {
            chartReadyTime &&
            <Button
              component="a" variant="primary"
              href={"/proxy/chart?namespace=" + namespace + "&pod=" + pod + "&time=" + chartReadyTime + "&download=true"}
              target="_blank">
              Download
            </Button>
          }
        </PageSection>
        <PageSection>
            { recording &&
              <div style={{ height: "100px" }}><Spinner size="xl" />Recording...</div>
            }
            { statusTimer.current && status !== "idle" &&
              <Progress
                min={0} max={4} value={statusValue(status)}
                label={ status } valueText={ status }
                measureLocation={ ProgressMeasureLocation.outside }
              />
            }
            { chartReadyTime &&
              <object type="image/svg+xml"
                data={ "/proxy/chart?namespace=" + namespace + "&pod=" + pod + "&time=" + chartReadyTime}
                alt={ "Flamegraph for " + namespace + "/" + pod }
              />
            }
        </PageSection>
      </Page>
    </div>
  );
}

export default App;

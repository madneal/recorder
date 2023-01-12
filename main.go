package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/fs"
	"io/ioutil"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Program struct {
	Name   string
	Target string
}

type CreateRuleReq struct {
	RuleType string `json:"ruleType"`
	Content  string `json:"content"`
	Name     string `json:"name"`
	Desc     string `json:"desc"`
	Status   bool   `json:"status"`
}

func main() {
	fileList := loadFiles()
	programs := make([]Program, 0)
	for _, file := range fileList {
		programsFromFile := convertFileToProgram(file)
		programs = append(programs, programsFromFile...)
	}
	for _, program := range programs {
		createRule(program)
	}
}

func createRule(program Program) {
	url := "http://121.36.215.221:8080/api/rule/createRule"
	createRuleReq := CreateRuleReq{
		RuleType: "github",
		Content:  program.Target,
		Name:     program.Name,
		Desc:     "",
		Status:   true,
	}
	jsonValue, _ := json.Marshal(&createRuleReq)
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonValue))
	if err != nil {
		fmt.Println(err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJVVUlEIjoiMGYyYTA1ZWMtNWY2My00MDUyLWIyZjQtOGQ4YmQyMGM0NTkxIiwiSUQiOjEsIlVzZXJuYW1lIjoiZ3NoYXJrIiwiTmlja05hbWUiOiLotoXnuqfnrqHnkIblkZgiLCJBdXRob3JpdHlJZCI6Ijg4OCIsIkJ1ZmZlclRpbWUiOjg2NDAwLCJleHAiOjE2NzQxMTY4NzUsImlzcyI6InFtUGx1cyIsIm5iZiI6MTY3Mjk4ODU3N30.Dlt9MdliP7cZ8sWrdXnuKAI5rfZ4PWBYwSDYkDXjxCo")
	_, err = http.DefaultClient.Do(req)
	if err != nil {
		fmt.Println(err)
		return
	}
}

func loadFiles() []string {
	fileList := make([]string, 0)
	dir, err := os.Open("bugcrowd/archive")
	if err != nil {
		fmt.Println(err)
		return fileList
	}
	defer dir.Close()
	filepath.Walk(dir.Name(), func(path string, info fs.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() {
			fileList = append(fileList, path)
		}
		return nil
	})
	return fileList
}

func convertFileToProgram(filepath string) []Program {
	programs := make([]Program, 0)
	data, err := ioutil.ReadFile(filepath)
	if err != nil {
		fmt.Println(err)
	}
	content := string(data)
	blocks := strings.Split(content, "Program Details : ")
	for _, block := range blocks {
		if strings.Contains(block, "Name") {
			var name string
			var target string
			nameReg, _ := regexp.Compile(`\*\*Name:\*\*\s(\w+)\b`)
			names := nameReg.FindStringSubmatch(block)
			if len(names) > 1 {
				name = names[1]
			}
			targetReg, _ := regexp.Compile("\\*\\*Target:\\*\\*\\s+`\\s+(.+)`")
			targets := targetReg.FindStringSubmatch(block)
			if len(targets) > 1 {
				target = targets[1]
			}
			if !checkifProgramExist(name, target, programs) && name != "" && target != "" {
				programs = append(programs, Program{
					Name:   name,
					Target: target,
				})
			}
		}
	}
	return programs
}

func checkifProgramExist(name, target string, programs []Program) bool {
	for _, program := range programs {
		if program.Name == name && program.Target == target {
			return true
		}
	}
	return false
}
